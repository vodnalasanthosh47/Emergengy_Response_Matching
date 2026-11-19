package com.emergency.resource.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
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
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.Provider;
import com.emergency.common.resource.ProviderKind;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.StorageClient;
import com.emergency.resource.exception.ConcurrentUpdateException;
import com.emergency.resource.exception.DependencyUnavailableException;
import com.emergency.resource.exception.ResourceNotFoundException;

import tools.jackson.databind.json.JsonMapper;

/** The repository must write exactly the format of docs/data-format.md §6. */
class CoordinatorResourceRepositoryTest {

    private static final String UUID = "8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77";
    private static final String ID = "res_tdr1v9_" + UUID;
    private static final String URL = "http://coordinator:8080/v1/data/resource:tdr1v9:" + UUID;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private CoordinatorResourceRepository repo;

    private final ResourceDocument doc = new ResourceDocument(ID, Category.WATER,
            new Provider("Akshaya NGO", ProviderKind.NGO, null), new Location(12.97, 77.59), "tdr1v9",
            new Capacity(new BigDecimal("500"), new BigDecimal("500"), "L"), json.readTree("{}"),
            ResourceStatus.AVAILABLE, Instant.parse("2026-11-19T10:00:00.250Z"), Instant.parse("2026-11-19T10:00:00.250Z"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://coordinator:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        repo = new CoordinatorResourceRepository(new StorageClient(builder, json));
    }

    private String record(ResourceDocument d, long version) {
        return """
                {"key":"resource:tdr1v9:%s","value":%s,"indexes":%s,"version":%d,"updatedAt":"2026-11-19T10:00:00.250Z"}"""
                .formatted(UUID, json.writeValueAsString(d),
                        json.writeValueAsString(CoordinatorResourceRepository.indexes(d)), version);
    }

    @Test
    void createWritesKeyIndexesAndCreateOnlyCondition() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"value":{"resourceId":"%s","type":"WATER","geoCell":"tdr1v9","status":"AVAILABLE",
                                  "capacity":{"total":500,"remaining":500,"unit":"L"},"details":{}},
                         "indexes":{"collection":"resource","type":"WATER","status":"AVAILABLE","geoCell":"tdr1v9"},
                         "expectedVersion":0}""".formatted(ID)))
                .andRespond(withStatus(HttpStatus.CREATED).body(record(doc, 1)).contentType(MediaType.APPLICATION_JSON));
        StoredResource stored = repo.create(doc);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.document()).isEqualTo(doc);
        server.verify();
    }

    @Test
    void updateKeepsIndexStatusInSyncAndIsConditional() {
        ResourceDocument offline = doc.withStatus(ResourceStatus.OFFLINE, Instant.parse("2026-11-19T10:20:00Z"));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"value":{"status":"OFFLINE"},"indexes":{"status":"OFFLINE"},"expectedVersion":4}"""))
                .andRespond(withSuccess(record(offline, 5), MediaType.APPLICATION_JSON));
        assertThat(repo.update(offline, 4).version()).isEqualTo(5);
        server.verify();
    }

    @Test
    void findAllQueriesEveryShardWithTheGivenFilters() {
        server.expect(requestTo("http://coordinator:8080/v1/query")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"collection":"resource","geoCells":[],"match":{"type":"WATER","status":"AVAILABLE"},"limit":50}""", true))
                .andRespond(withSuccess("{\"records\":[" + record(doc, 2) + "]}", MediaType.APPLICATION_JSON));
        assertThat(repo.findAll(Category.WATER, ResourceStatus.AVAILABLE, 50))
                .containsExactly(new StoredResource(doc, 2));
        server.verify();
    }

    @Test
    void findAllWithoutFiltersMatchesEverything() {
        server.expect(requestTo("http://coordinator:8080/v1/query"))
                .andExpect(content().json("""
                        {"collection":"resource","geoCells":[],"match":{},"limit":10}""", true))
                .andRespond(withSuccess("{\"records\":[]}", MediaType.APPLICATION_JSON));
        assertThat(repo.findAll(null, null, 10)).isEmpty();
        server.verify();
    }

    @Test
    void storageOutcomesBecomeServiceOutcomes() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> repo.update(doc, 4)).isInstanceOf(ConcurrentUpdateException.class);
        assertThatThrownBy(() -> repo.update(doc, 4)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> repo.find(ID)).isInstanceOf(DependencyUnavailableException.class);
        assertThat(repo.find(ID)).isEmpty();
        assertThatThrownBy(() -> repo.create(doc)).isInstanceOf(IllegalStateException.class).hasMessageContaining("bug");
        server.verify();
    }
}
