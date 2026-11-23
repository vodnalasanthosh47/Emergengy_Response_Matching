package com.emergency.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.emergency.common.events.EventTopology;
import com.emergency.common.events.RematchRequestedEvent;
import com.emergency.common.events.RequestCreatedEvent;
import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.StorageKeys;
import com.emergency.matching.domain.RematchSweeper;
import com.emergency.matching.support.FakeCoordinator;
import com.emergency.matching.support.Fixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service: a real RabbitMQ (Docker) with the topology this service declares,
 * real HTTP, and a fake coordinator that enforces expectedVersion like the real one.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"emergency.event-attempts=2", "emergency.event-retry-delay=100ms"})
class MatchingServiceApplicationTest {

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
    AmqpAdmin admin;

    @Autowired
    RematchSweeper sweeper;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void storageUp() {
        COORDINATOR.down = false;
    }

    private RequestDocument storeRequest(Category type, String details) {
        RequestDocument r = Fixtures.request(type, details);
        COORDINATOR.put(StorageKeys.request(r.requestId()), r, java.util.Map.of(
                "collection", "request", "type", type.name(), "status", r.status().name(), "geoCell", r.geoCell()));
        return r;
    }

    private ResourceDocument storeResource(Category type, double dLat, String total) {
        ResourceDocument r = Fixtures.resource(type, dLat, total, total);
        COORDINATOR.put(StorageKeys.resourceIdToKey(r.resourceId()), r, java.util.Map.of(
                "collection", "resource", "type", type.name(), "status", r.status().name(), "geoCell", r.geoCell()));
        return r;
    }

    private JsonNode storedRequest(String id) {
        return COORDINATOR.record(StorageKeys.request(id));
    }

    private JsonNode storedResource(ResourceDocument r) {
        return COORDINATOR.record(StorageKeys.resourceIdToKey(r.resourceId()));
    }

    /** Publishes RequestCreated exactly as the Request Service does. */
    private void publish(RequestDocument request) {
        RequestCreatedEvent event = RequestCreatedEvent.of(RequestView.of(request, 1), Instant.now());
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        p.setMessageId(event.eventId());
        rabbit.send(EventTopology.EXCHANGE, EventTopology.requestCreatedRoutingKey(request.type()),
                new Message(json.writeValueAsBytes(event), p));
    }

    /** Stores a request and a fitting resource near (lat, lng), away from the other tests' requests. */
    private RequestDocument storeAt(double lat, double lng, Category type, String details, RequestStatus status) {
        RequestDocument r = Fixtures.request(java.util.UUID.randomUUID().toString(), type, lat, lng, details, status);
        COORDINATOR.put(StorageKeys.request(r.requestId()), r, java.util.Map.of(
                "collection", "request", "type", type.name(), "status", status.name(), "geoCell", r.geoCell()));
        return r;
    }

    private ResourceDocument storeResourceAt(double lat, double lng, Category type, String total) {
        ResourceDocument r = Fixtures.resource(type, lat, lng, total, total, ResourceStatus.AVAILABLE, null);
        COORDINATOR.put(StorageKeys.resourceIdToKey(r.resourceId()), r, java.util.Map.of(
                "collection", "resource", "type", type.name(), "status", r.status().name(), "geoCell", r.geoCell()));
        return r;
    }

    private String status(String requestId) {
        return storedRequest(requestId).path("value").path("status").stringValue();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("timed out waiting for: " + what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private long queued(String queue) {
        return admin.getQueueInfo(queue).getMessageCount();
    }

    private ResponseEntity<String> post(String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build()
                .post().uri(path).retrieve().toEntity(String.class);
    }

    @Test
    void anEventGetsTheRequestMatched() throws Exception {
        ResourceDocument resource = storeResource(Category.WATER, 0.001, "500");
        RequestDocument request = storeRequest(Category.WATER, "{\"quantity\":20,\"unit\":\"L\"}");

        publish(request);

        await("request MATCHED", () -> "MATCHED".equals(storedRequest(request.requestId()).path("value").path("status").stringValue()));
        JsonNode stored = storedRequest(request.requestId());
        assertThat(stored.path("value").path("assignedResourceId").stringValue()).isEqualTo(resource.resourceId());
        assertThat(stored.path("indexes").path("status").stringValue()).isEqualTo("MATCHED");
        assertThat(storedResource(resource).path("value").path("capacity").path("remaining").intValue()).isEqualTo(480);
        await("queue drained", () -> queued(EventTopology.MATCHING_QUEUE) == 0);
    }

    /** DoD: re-publishing the same RequestCreated event does not cause a second claim. */
    @Test
    void aRedeliveredEventDoesNotClaimTwice() throws Exception {
        ResourceDocument resource = storeResource(Category.SHELTER, 0.001, "50");
        RequestDocument request = storeRequest(Category.SHELTER, "{\"people\":10}");
        publish(request);
        await("request MATCHED", () -> "MATCHED".equals(storedRequest(request.requestId()).path("value").path("status").stringValue()));
        long requestVersion = storedRequest(request.requestId()).path("version").longValue();
        long resourceVersion = storedResource(resource).path("version").longValue();

        publish(request);
        publish(request);
        await("redeliveries consumed", () -> queued(EventTopology.MATCHING_QUEUE) == 0);
        Thread.sleep(300); // let the listener finish the last one

        assertThat(storedRequest(request.requestId()).path("version").longValue()).isEqualTo(requestVersion);
        assertThat(storedResource(resource).path("version").longValue()).as("no second claim").isEqualTo(resourceVersion);
        assertThat(storedResource(resource).path("value").path("capacity").path("remaining").intValue()).isEqualTo(40);
        assertThat(queued(EventTopology.MATCHING_DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void noResourceMakesTheRequestUnmatched() throws Exception {
        RequestDocument request = storeRequest(Category.MEDICINE, "{\"name\":\"Rare drug\",\"quantity\":1}");
        publish(request);
        await("request UNMATCHED", () -> "UNMATCHED".equals(storedRequest(request.requestId()).path("value").path("status").stringValue()));
        assertThat(storedRequest(request.requestId()).path("indexes").path("status").stringValue()).isEqualTo("UNMATCHED");
    }

    /** The rematch sweeper's message goes to the same queue and gets a waiting request matched. */
    @Test
    void aRematchRequestedEventGetsAnUnmatchedRequestMatched() throws Exception {
        RequestDocument request = storeAt(28.6139, 77.2090, Category.WATER, "{\"quantity\":20,\"unit\":\"L\"}",
                RequestStatus.UNMATCHED);
        ResourceDocument resource = storeResourceAt(28.6149, 77.2090, Category.WATER, "100"); // registered later

        RematchRequestedEvent event = RematchRequestedEvent.of(request.requestId(), Category.WATER,
                RematchRequestedEvent.Reason.UNMATCHED, Instant.now());
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(EventTopology.EXCHANGE, EventTopology.requestRematchRoutingKey(Category.WATER),
                new Message(json.writeValueAsBytes(event), p));

        await("request MATCHED", () -> "MATCHED".equals(status(request.requestId())));
        assertThat(storedRequest(request.requestId()).path("value").path("assignedResourceId").stringValue())
                .isEqualTo(resource.resourceId());
        assertThat(storedResource(resource).path("value").path("capacity").path("remaining").intValue()).isEqualTo(80);
    }

    /** One pass of the real sweeper: storage query → RabbitMQ → listener → MATCHED. */
    @Test
    void theSweeperRequeuesAnUnmatchedRequestThatCanNowBeServed() throws Exception {
        RequestDocument request = storeAt(19.0760, 72.8777, Category.FOOD, "{\"quantity\":10,\"unit\":\"MEALS\"}",
                RequestStatus.UNMATCHED);
        ResourceDocument resource = storeResourceAt(19.0770, 72.8777, Category.FOOD, "50"); // registered later

        RematchSweeper.Result result = sweeper.sweep();

        assertThat(result.published()).isGreaterThanOrEqualTo(1);
        await("request MATCHED", () -> "MATCHED".equals(status(request.requestId())));
        assertThat(storedRequest(request.requestId()).path("value").path("assignedResourceId").stringValue())
                .isEqualTo(resource.resourceId());
        assertThat(storedResource(resource).path("value").path("capacity").path("remaining").intValue()).isEqualTo(40);
        // The pass also re-queued other tests' waiting requests: let them settle (all
        // harmless, each is re-read and left alone or matched once).
        await("queue drained", () -> queued(EventTopology.MATCHING_QUEUE) == 0);
        Thread.sleep(500);
        assertThat(queued(EventTopology.MATCHING_DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void malformedMessagesGoToTheDeadLetterQueue() throws Exception {
        long before = queued(EventTopology.MATCHING_DEAD_LETTER_QUEUE);
        rabbit.send(EventTopology.EXCHANGE, "request.created.water", new Message("not json".getBytes(), new MessageProperties()));
        rabbit.send(EventTopology.EXCHANGE, "request.created.water",
                new Message("{\"eventType\":\"SomethingElse\",\"schemaVersion\":1}".getBytes(), new MessageProperties()));
        await("2 messages dead-lettered", () -> queued(EventTopology.MATCHING_DEAD_LETTER_QUEUE) == before + 2);
        assertThat(rabbit.receive(EventTopology.MATCHING_DEAD_LETTER_QUEUE, 1000)).isNotNull();
        assertThat(rabbit.receive(EventTopology.MATCHING_DEAD_LETTER_QUEUE, 1000)).isNotNull();
    }

    @Test
    void storageDownSendsTheEventToTheDeadLetterQueueAfterRetrying() throws Exception {
        RequestDocument request = storeRequest(Category.FOOD, "{\"quantity\":5,\"unit\":\"MEALS\"}");
        long before = queued(EventTopology.MATCHING_DEAD_LETTER_QUEUE);
        COORDINATOR.down = true;
        publish(request);
        await("event dead-lettered", () -> queued(EventTopology.MATCHING_DEAD_LETTER_QUEUE) == before + 1);
        COORDINATOR.down = false;
        rabbit.receive(EventTopology.MATCHING_DEAD_LETTER_QUEUE, 1000);

        // Recovery path: match it by hand once storage is back.
        storeResource(Category.FOOD, 0.001, "100");
        ResponseEntity<String> r = post("/match/" + request.requestId());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(r.getBody()).path("request").path("status").stringValue()).isEqualTo("MATCHED");
    }

    @Test
    void postMatchOverHttp() throws Exception {
        RequestDocument request = storeRequest(Category.TRANSPORT, "{\"people\":4}");
        ResponseEntity<String> none = post("/match/" + request.requestId());
        assertThat(none.getStatusCode().value()).isEqualTo(422);
        assertThat(json.readTree(none.getBody()).path("error").stringValue()).isEqualTo("NO_MATCH");

        ResourceDocument car = storeResource(Category.TRANSPORT, 0.002, "4");
        ResponseEntity<String> ok = post("/match/" + request.requestId());
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(ok.getBody());
        assertThat(body.path("resourceId").stringValue()).isEqualTo(car.resourceId());
        assertThat(body.path("distanceKm").doubleValue()).isBetween(0.2, 0.25);
        assertThat(body.path("resource").path("status").stringValue()).isEqualTo("DEPLETED");

        assertThat(post("/match/" + request.requestId()).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/match/00000000-0000-4000-8000-000000000000").getStatusCode().value()).isEqualTo(404);
        assertThat(post("/match/not-a-uuid").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void healthAndMetrics() {
        RestClient http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
        assertThat(http.get().uri("/actuator/health/readiness").retrieve().toEntity(String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.get().uri("/actuator/prometheus").retrieve().body(String.class)).contains("application=\"matching-service\"");
    }
}
