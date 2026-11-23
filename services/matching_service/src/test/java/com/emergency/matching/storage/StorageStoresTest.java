package com.emergency.matching.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.StorageClient;
import com.emergency.common.storage.StorageKeys;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.Versioned;
import com.emergency.matching.support.Fixtures;

import tools.jackson.databind.json.JsonMapper;

/** Both adapters must read and write exactly the documented format (docs/data-format.md §5, §6). */
class StorageStoresTest {

    private static final String BASE = "http://coordinator:8080";

    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private StorageRequestStore requests;
    private StorageResourceStore resources;

    private final RequestDocument request = Fixtures.request("f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d", Category.WATER,
            Fixtures.LAT, Fixtures.LNG, "{\"quantity\":20,\"unit\":\"L\"}", RequestStatus.PENDING);
    private final ResourceDocument resource = Fixtures.resource(Category.WATER, 0.001, "500", "500");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        StorageClient storage = new StorageClient(builder, json);
        requests = new StorageRequestStore(storage);
        resources = new StorageResourceStore(storage);
    }

    private String record(String key, Object value, Object indexes, long version) {
        return """
                {"key":"%s","value":%s,"indexes":%s,"version":%d,"updatedAt":"2026-11-19T10:15:00Z"}"""
                .formatted(key, json.writeValueAsString(value), json.writeValueAsString(indexes), version);
    }

    private String requestUrl() {
        return BASE + "/v1/data/request:" + request.requestId();
    }

    private String resourceUrl() {
        return BASE + "/v1/data/" + StorageKeys.resourceIdToKey(resource.resourceId());
    }

    @Test
    void readsARequestWithItsVersion() {
        server.expect(requestTo(requestUrl())).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(record("request:" + request.requestId(), request,
                        StorageRequestStore.indexes(request), 4), MediaType.APPLICATION_JSON));
        Versioned<RequestDocument> got = requests.find(request.requestId()).orElseThrow();
        assertThat(got.document()).isEqualTo(request);
        assertThat(got.version()).isEqualTo(4);
    }

    @Test
    void matchingARequestWritesStatusIndexAndAssignmentConditionally() {
        RequestDocument matched = request.matchedTo(resource.resourceId(), Fixtures.T0);
        server.expect(requestTo(requestUrl())).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"value":{"status":"MATCHED","assignedResourceId":"%s"},
                         "indexes":{"collection":"request","type":"WATER","status":"MATCHED","geoCell":"tdr1v9"},
                         "expectedVersion":4}""".formatted(resource.resourceId())))
                .andRespond(withSuccess(record("request:" + request.requestId(), matched,
                        StorageRequestStore.indexes(matched), 5), MediaType.APPLICATION_JSON));
        assertThat(requests.update(matched, 4).version()).isEqualTo(5);
        server.verify();
    }

    @Test
    void waitingRequestsAreFoundByTheStatusIndexOnAllShards() {
        RequestDocument unmatched = Fixtures.request("0c9f8b52-6a1e-4f43-9d0b-1f2e3d4c5b6a", Category.WATER, Fixtures.LAT,
                Fixtures.LNG, "{\"quantity\":20,\"unit\":\"L\"}", RequestStatus.UNMATCHED);
        server.expect(requestTo(BASE + "/v1/query")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"collection":"request","geoCells":[],"match":{"status":"UNMATCHED"},"limit":1000}""", true))
                .andRespond(withSuccess("{\"records\":[" + record("request:" + unmatched.requestId(), unmatched,
                        StorageRequestStore.indexes(unmatched), 3) + "]}", MediaType.APPLICATION_JSON));
        assertThat(requests.withStatus(RequestStatus.UNMATCHED, 1000)).containsExactly(unmatched);
        server.verify();
    }

    @Test
    void candidateSearchAsksForAvailableResourcesInTheCells() {
        server.expect(requestTo(BASE + "/v1/query")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"collection":"resource","geoCells":["tdr1v9","tdr1vc"],
                         "match":{"type":"WATER","status":"AVAILABLE"},"limit":100}""", true))
                .andRespond(withSuccess("{\"records\":[" + record(StorageKeys.resourceIdToKey(resource.resourceId()),
                        resource, StorageResourceStore.indexes(resource), 7) + "]}", MediaType.APPLICATION_JSON));
        List<Versioned<ResourceDocument>> found = resources.findAvailable(Category.WATER, List.of("tdr1v9", "tdr1vc"), 100);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).document()).isEqualTo(resource);
        assertThat(found.get(0).version()).isEqualTo(7);
    }

    @Test
    void claimingWritesCapacityAndStatusIndexConditionally() {
        ResourceDocument claimed = resource.withCapacity(new Capacity(new BigDecimal("500"), BigDecimal.ZERO, "L"),
                ResourceStatus.DEPLETED, Fixtures.T0);
        server.expect(requestTo(resourceUrl())).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"value":{"capacity":{"total":500,"remaining":0,"unit":"L"},"status":"DEPLETED"},
                         "indexes":{"collection":"resource","type":"WATER","status":"DEPLETED","geoCell":"%s"},
                         "expectedVersion":7}""".formatted(resource.geoCell())))
                .andRespond(withSuccess(record(StorageKeys.resourceIdToKey(resource.resourceId()), claimed,
                        StorageResourceStore.indexes(claimed), 8), MediaType.APPLICATION_JSON));
        assertThat(resources.update(claimed, 7).version()).isEqualTo(8);
        server.verify();
    }

    @Test
    void storageOutcomesBecomeDomainOutcomes() {
        server.expect(requestTo(requestUrl())).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(requestUrl())).andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        server.expect(requestTo(resourceUrl())).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(requestUrl())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/v1/query")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(requestUrl())).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(resourceUrl())).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> requests.update(request, 1)).isInstanceOf(ConcurrentUpdateException.class);
        assertThatThrownBy(() -> requests.update(request, 1)).isInstanceOf(ConcurrentUpdateException.class); // deleted
        assertThatThrownBy(() -> resources.update(resource, 1)).isInstanceOf(ConcurrentUpdateException.class);
        assertThatThrownBy(() -> requests.find(request.requestId())).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> resources.findAvailable(Category.WATER, List.of("tdr1v9"), 10))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(requests.find(request.requestId())).isEmpty();
        assertThat(resources.find(resource.resourceId())).isEmpty();
        server.verify();
    }
}
