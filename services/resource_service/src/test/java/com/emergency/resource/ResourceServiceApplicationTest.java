package com.emergency.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.emergency.resource.support.FakeCoordinator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole application over real HTTP against a fake coordinator. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceServiceApplicationTest {

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

    private final JsonMapper json = JsonMapper.builder().build();

    private ResponseEntity<String> call(String method, String path, String body) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build()
                .method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    @Test
    void registerReadListAndChangeStatus() {
        ResponseEntity<String> created = call("POST", "/resources", """
                {"type":"WATER","location":{"lat":12.9716,"lng":77.5946},
                 "provider":{"name":"Akshaya NGO","kind":"NGO","contact":"+91-80000-00000"},
                 "capacity":{"total":500}}""");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode resource = json.readTree(created.getBody());
        String id = resource.get("resourceId").stringValue();
        assertThat(id).startsWith("res_tdr1v9_");
        assertThat(created.getHeaders().getLocation()).hasToString("/resources/" + id);
        assertThat(resource.get("capacity")).isEqualTo(json.readTree("{\"total\":500,\"remaining\":500,\"unit\":\"L\"}"));

        // Stored in the documented format, under resource:{cell}:{uuid}.
        String key = "resource:tdr1v9:" + id.substring("res_tdr1v9_".length());
        JsonNode record = COORDINATOR.record(key);
        assertThat(record).isNotNull();
        assertThat(record.get("indexes")).isEqualTo(json.readTree("""
                {"collection":"resource","type":"WATER","status":"AVAILABLE","geoCell":"tdr1v9"}"""));
        assertThat(record.get("value").has("version")).isFalse();
        assertThat(record.get("value").get("capacity").get("total").isIntegralNumber()).isTrue();

        assertThat(json.readTree(call("GET", "/resources/" + id, null).getBody())).isEqualTo(resource);

        ResponseEntity<String> list = call("GET", "/resources?type=WATER&status=AVAILABLE", null);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(list.getBody()).get("resources").get(0).get("resourceId").stringValue()).isEqualTo(id);

        ResponseEntity<String> offline = call("PUT", "/resources/" + id + "/status", "{\"status\":\"OFFLINE\"}");
        assertThat(offline.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(offline.getBody()).get("version").longValue()).isEqualTo(2);
        assertThat(COORDINATOR.record(key).get("indexes").get("status").stringValue()).isEqualTo("OFFLINE");
        assertThat(json.readTree(call("GET", "/resources?status=AVAILABLE&type=WATER", null).getBody()).get("resources"))
                .isEmpty();
        assertThat(call("PUT", "/resources/" + id + "/status", "{\"status\":\"DEPLETED\"}").getStatusCode().value())
                .isEqualTo(400);

        assertThat(call("GET", "/resources/res_tdr1v9_00000000-0000-4000-8000-000000000000", null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(call("GET", "/resources/not-an-id", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void storageDownIs503AndNotReady() {
        COORDINATOR.down = true;
        try {
            ResponseEntity<String> r = call("POST", "/resources", """
                    {"type":"SHELTER","location":{"lat":1,"lng":2},"provider":{"name":"School","kind":"GOVT"},
                     "capacity":{"total":30}}""");
            assertThat(r.getStatusCode().value()).isEqualTo(503);
            assertThat(json.readTree(r.getBody()).get("error").stringValue()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(call("GET", "/actuator/health/readiness", null).getStatusCode().value()).isEqualTo(503);
            assertThat(call("GET", "/actuator/health/liveness", null).getStatusCode().value()).isEqualTo(200);
        } finally {
            COORDINATOR.down = false;
        }
    }

    @Test
    void healthAndMetricsEndpoints() {
        assertThat(call("GET", "/actuator/health/readiness", null).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> metrics = call("GET", "/actuator/prometheus", null);
        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody()).contains("application=\"resource-service\"");
    }
}
