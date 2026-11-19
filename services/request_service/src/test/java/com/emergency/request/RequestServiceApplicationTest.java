package com.emergency.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.emergency.common.events.EventTopology;
import com.emergency.request.support.FakeCoordinator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole application: real HTTP, real RabbitMQ (Docker), and a fake coordinator that
 * enforces expectedVersion like the real one. Create → event → read → status change.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestServiceApplicationTest {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static final FakeCoordinator COORDINATOR;

    static {
        try {
            COORDINATOR = new FakeCoordinator();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void coordinator(DynamicPropertyRegistry registry) {
        registry.add("emergency.coordinator-url", COORDINATOR::url);
    }

    @AfterAll
    static void stopCoordinator() {
        COORDINATOR.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    private final JsonMapper json = JsonMapper.builder().build();

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }) // let tests inspect error codes
                .build();
    }

    private ResponseEntity<String> call(String method, String path, String body) {
        RestClient.RequestBodySpec spec = http().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    @Test
    void createPublishReadAndChangeStatus() {
        // Bind a queue the way the Matching Service will, to see the event.
        TopicExchange exchange = new TopicExchange(EventTopology.EXCHANGE, true, false);
        admin.declareExchange(exchange);
        // Not an AnonymousQueue: that is auto-delete and vanishes after the first receive().
        Queue queue = new Queue("test." + java.util.UUID.randomUUID(), false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(EventTopology.REQUEST_CREATED_BINDING));

        ResponseEntity<String> created = call("POST", "/requests", """
                {"type":"WATER","location":{"lat":12.9716,"lng":77.5946},"details":{"quantity":20}}""");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode request = json.readTree(created.getBody());
        String id = request.get("requestId").stringValue();
        assertThat(created.getHeaders().getLocation()).hasToString("/requests/" + id);
        assertThat(request.get("geoCell").stringValue()).isEqualTo("tdr1v9");
        assertThat(request.get("version").longValue()).isEqualTo(1);

        // Stored in the documented format, under request:{id}.
        JsonNode record = COORDINATOR.record("request:" + id);
        assertThat(record).isNotNull();
        assertThat(record.get("indexes")).isEqualTo(json.readTree("""
                {"collection":"request","type":"WATER","status":"PENDING","geoCell":"tdr1v9"}"""));
        assertThat(record.get("value").has("version")).isFalse();

        // Published.
        Message event = rabbit.receive(queue.getName(), 5000);
        assertThat(event).isNotNull();
        assertThat(json.readTree(event.getBody()).get("request").get("requestId").stringValue()).isEqualTo(id);
        assertThat(event.getMessageProperties().getReceivedRoutingKey()).isEqualTo("request.created.water");

        // Read back.
        ResponseEntity<String> got = call("GET", "/requests/" + id, null);
        assertThat(got.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(got.getBody())).isEqualTo(request);

        // Status: legal, idempotent, reserved, illegal.
        ResponseEntity<String> cancelled = call("PUT", "/requests/" + id + "/status", "{\"status\":\"CANCELLED\"}");
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(cancelled.getBody()).get("version").longValue()).isEqualTo(2);
        assertThat(COORDINATOR.record("request:" + id).get("indexes").get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(call("PUT", "/requests/" + id + "/status", "{\"status\":\"CANCELLED\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(call("PUT", "/requests/" + id + "/status", "{\"status\":\"MATCHED\"}").getStatusCode().value()).isEqualTo(400);
        ResponseEntity<String> illegal = call("PUT", "/requests/" + id + "/status", "{\"status\":\"PENDING\"}");
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);
        assertThat(json.readTree(illegal.getBody()).get("error").stringValue()).isEqualTo("CONFLICT");

        assertThat(call("GET", "/requests/00000000-0000-4000-8000-000000000000", null).getStatusCode().value()).isEqualTo(404);
        assertThat(call("GET", "/requests/not-a-uuid", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void storageDownIs503AndNothingIsPublished() {
        int before = COORDINATOR.size();
        COORDINATOR.down = true;
        try {
            ResponseEntity<String> r = call("POST", "/requests", """
                    {"type":"SHELTER","location":{"lat":1,"lng":2},"details":{"people":3}}""");
            assertThat(r.getStatusCode().value()).isEqualTo(503);
            assertThat(json.readTree(r.getBody()).get("error").stringValue()).isEqualTo("SERVICE_UNAVAILABLE");
            ResponseEntity<String> ready = call("GET", "/actuator/health/readiness", null);
            assertThat(ready.getStatusCode().value()).as("not ready while storage is down").isEqualTo(503);
        } finally {
            COORDINATOR.down = false;
        }
        assertThat(COORDINATOR.size()).isEqualTo(before);
    }

    @Test
    void healthAndMetricsEndpoints() {
        assertThat(call("GET", "/actuator/health/liveness", null).getStatusCode().value()).isEqualTo(200);
        assertThat(call("GET", "/actuator/health/readiness", null).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> metrics = call("GET", "/actuator/prometheus", null);
        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody()).contains("http_server_requests").contains("application=\"request-service\"");
    }
}
