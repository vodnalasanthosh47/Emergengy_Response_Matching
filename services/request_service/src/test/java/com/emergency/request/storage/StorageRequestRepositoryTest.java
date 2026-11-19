package com.emergency.request.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.storage.StorageClient;
import com.emergency.request.domain.ConcurrentUpdateException;
import com.emergency.request.domain.DependencyUnavailableException;
import com.emergency.request.domain.RequestNotFoundException;
import com.emergency.request.domain.StoredRequest;

import tools.jackson.databind.json.JsonMapper;

/** The repository must write exactly the format of docs/data-format.md §5. */
class StorageRequestRepositoryTest {

    private static final String ID = "f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d";
    private static final String URL = "http://coordinator:8080/v1/data/request:" + ID;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private StorageRequestRepository repo;

    private final RequestDocument doc = new RequestDocument(ID, Category.WATER, new Location(12.9716, 77.5946), "tdr1v9",
            RequestStatus.PENDING, null, json.readTree("{\"quantity\":20,\"unit\":\"L\"}"), null,
            Instant.parse("2026-11-19T10:15:00.112Z"), Instant.parse("2026-11-19T10:15:00.112Z"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://coordinator:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        repo = new StorageRequestRepository(new StorageClient(builder, json));
    }

    private String record(RequestDocument d, long version) {
        return """
                {"key":"request:%s","value":%s,"indexes":%s,"version":%d,"updatedAt":"2026-11-19T10:15:00.112Z"}"""
                .formatted(ID, json.writeValueAsString(d), json.writeValueAsString(StorageRequestRepository.indexes(d)), version);
    }

    @Test
    void createWritesKeyIndexesAndCreateOnlyCondition() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"value":{"requestId":"%s","type":"WATER","geoCell":"tdr1v9","status":"PENDING","assignedResourceId":null,
                                  "details":{"quantity":20,"unit":"L"},"createdAt":"2026-11-19T10:15:00.112Z"},
                         "indexes":{"collection":"request","type":"WATER","status":"PENDING","geoCell":"tdr1v9"},
                         "expectedVersion":0}""".formatted(ID)))
                .andRespond(withStatus(HttpStatus.CREATED).body(record(doc, 1)).contentType(MediaType.APPLICATION_JSON));
        StoredRequest stored = repo.create(doc);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.document()).isEqualTo(doc);
        server.verify();
    }

    @Test
    void updateKeepsIndexStatusInSyncAndIsConditional() {
        RequestDocument cancelled = doc.withStatus(RequestStatus.CANCELLED, Instant.parse("2026-11-19T10:20:00Z"));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"value":{"status":"CANCELLED"},"indexes":{"status":"CANCELLED"},"expectedVersion":4}"""))
                .andRespond(withSuccess(record(cancelled, 5), MediaType.APPLICATION_JSON));
        assertThat(repo.update(cancelled, 4).version()).isEqualTo(5);
        server.verify();
    }

    @Test
    void storageOutcomesBecomeDomainOutcomes() {
        // The mock server needs every expectation before the first call; they are answered in order.
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL + "?expectedVersion=1")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> repo.update(doc, 4)).isInstanceOf(ConcurrentUpdateException.class);
        assertThatThrownBy(() -> repo.update(doc, 4)).isInstanceOf(RequestNotFoundException.class);
        assertThatThrownBy(() -> repo.find(ID)).isInstanceOf(DependencyUnavailableException.class);
        assertThat(repo.find(ID)).isEmpty();
        repo.delete(ID, 1); // already gone: fine
        assertThatThrownBy(() -> repo.create(doc)).isInstanceOf(IllegalStateException.class).hasMessageContaining("bug");
        server.verify();
    }
}
