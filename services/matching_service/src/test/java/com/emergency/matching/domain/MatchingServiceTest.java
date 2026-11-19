package com.emergency.matching.domain;

import static com.emergency.matching.support.Fixtures.LAT;
import static com.emergency.matching.support.Fixtures.LNG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.matching.support.Fixtures;
import com.emergency.matching.support.InMemoryStores;

class MatchingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:15:00.123456Z");

    private InMemoryStores.Requests requests;
    private InMemoryStores.Resources resources;
    private MatchingService service;

    @BeforeEach
    void setUp() {
        requests = new InMemoryStores.Requests();
        resources = new InMemoryStores.Resources();
        service = new MatchingService(requests, resources, new CandidateRanking(), Clock.fixed(NOW, ZoneOffset.UTC), 100);
    }

    private RequestDocument water(int litres) {
        RequestDocument r = Fixtures.request(Category.WATER, "{\"quantity\":" + litres + ",\"unit\":\"L\"}");
        requests.put(r);
        return r;
    }

    private ResourceDocument waterResource(double dLat, String remaining) {
        ResourceDocument r = Fixtures.resource(Category.WATER, dLat, "500", remaining);
        resources.put(r);
        return r;
    }

    private BigDecimal remaining(ResourceDocument r) {
        return resources.get(r.resourceId()).capacity().remaining();
    }

    @Test
    void claimsTheNearestFittingResourceAndMarksTheRequestMatched() {
        RequestDocument req = water(20);
        ResourceDocument far = waterResource(0.004, "500");
        ResourceDocument near = waterResource(0.001, "500");

        MatchOutcome outcome = service.match(req.requestId());

        MatchOutcome.Matched m = (MatchOutcome.Matched) outcome;
        assertThat(m.resource().resourceId()).isEqualTo(near.resourceId());
        assertThat(m.distanceKm()).isBetween(0.10, 0.12);
        assertThat(remaining(near)).isEqualByComparingTo("480");
        assertThat(remaining(far)).isEqualByComparingTo("500");
        RequestDocument stored = requests.get(req.requestId());
        assertThat(stored.status()).isEqualTo(RequestStatus.MATCHED);
        assertThat(stored.assignedResourceId()).isEqualTo(near.resourceId());
        assertThat(stored.updatedAt()).isEqualTo(Instant.parse("2026-11-19T10:15:00.123Z"));
        assertThat(m.request().version()).isEqualTo(2);
        assertThat(m.resource().version()).isEqualTo(2);
    }

    @Test
    void takingTheLastOfAResourceDepletesIt() {
        RequestDocument req = water(20);
        ResourceDocument exact = waterResource(0.001, "20");
        service.match(req.requestId());
        assertThat(resources.get(exact.resourceId()).capacity().remaining()).isEqualByComparingTo("0");
        assertThat(resources.get(exact.resourceId()).status()).isEqualTo(ResourceStatus.DEPLETED);
    }

    @Test
    void decimalWaterIsSubtractedExactly() {
        RequestDocument req = Fixtures.request(Category.WATER, "{\"quantity\":2.5,\"unit\":\"L\"}");
        requests.put(req);
        ResourceDocument r = waterResource(0.001, "10.125");
        service.match(req.requestId());
        assertThat(remaining(r)).isEqualByComparingTo("7.625");
    }

    @Test
    void noCandidateMakesTheRequestUnmatched() {
        RequestDocument req = water(20);
        waterResource(0.001, "10");                    // too small
        resources.put(Fixtures.resource(Category.FOOD, 0.001, "500", "500")); // wrong type
        resources.put(Fixtures.resource(Category.WATER, 1.0, "500", "500"));  // ~111 km away, outside the 9 cells

        MatchOutcome outcome = service.match(req.requestId());

        assertThat(outcome).isInstanceOf(MatchOutcome.NoMatch.class);
        assertThat(requests.get(req.requestId()).status()).isEqualTo(RequestStatus.UNMATCHED);
        assertThat(resources.updates.get()).as("nothing claimed").isZero();
    }

    @Test
    void anUnmatchedRequestIsMatchedOnceAResourceAppears() {
        RequestDocument req = water(20);
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.NoMatch.class);
        int writes = requests.updates.get();
        assertThat(service.match(req.requestId())).as("still nothing: no second write").isInstanceOf(MatchOutcome.NoMatch.class);
        assertThat(requests.updates.get()).isEqualTo(writes);

        waterResource(0.001, "500");
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.Matched.class);
    }

    /** Redelivered events and repeated calls must never claim twice. */
    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"MATCHED", "IN_PROGRESS", "FULFILLED", "CANCELLED"})
    void requestsThatAreNotMatchableAreLeftAlone(RequestStatus status) {
        RequestDocument req = Fixtures.request(java.util.UUID.randomUUID().toString(), Category.WATER, LAT, LNG,
                "{\"quantity\":20,\"unit\":\"L\"}", status);
        requests.put(req);
        waterResource(0.001, "500");
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.NotMatchable.class);
        assertThat(resources.updates.get()).isZero();
        assertThat(requests.updates.get()).isZero();
    }

    @Test
    void matchingTheSameRequestTwiceClaimsOnce() {
        RequestDocument req = water(20);
        ResourceDocument r = waterResource(0.001, "500");
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.Matched.class);
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.NotMatchable.class);
        assertThat(remaining(r)).isEqualByComparingTo("480");
    }

    @Test
    void unknownAndMalformedIds() {
        assertThat(service.match("00000000-0000-4000-8000-000000000000")).isInstanceOf(MatchOutcome.NotFound.class);
        assertThatThrownBy(() -> service.match("not-a-uuid")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.match("request:x")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void claimConflictOnAResourceThatStillFitsIsRetried() {
        RequestDocument req = water(20);
        ResourceDocument r = waterResource(0.001, "500");
        // Someone else takes 100 L just before our claim lands.
        resources.beforeUpdate = id -> {
            resources.beforeUpdate = x -> { };
            ResourceDocument cur = resources.get(id);
            resources.concurrentWrite(cur.withCapacity(new Capacity(cur.capacity().total(), new BigDecimal("400"), "L"),
                    ResourceStatus.AVAILABLE, NOW));
        };
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.Matched.class);
        assertThat(remaining(r)).as("our 20 came off the new 400").isEqualByComparingTo("380");
    }

    @Test
    void claimConflictOnAResourceThatNoLongerFitsMovesToTheNext() {
        RequestDocument req = water(20);
        ResourceDocument near = waterResource(0.001, "500");
        ResourceDocument far = waterResource(0.003, "500");
        resources.beforeUpdate = id -> {
            if (id.equals(near.resourceId())) {
                resources.beforeUpdate = x -> { };
                ResourceDocument cur = resources.get(id);
                resources.concurrentWrite(cur.withCapacity(new Capacity(cur.capacity().total(), new BigDecimal("5"), "L"),
                        ResourceStatus.AVAILABLE, NOW));
            }
        };
        MatchOutcome.Matched m = (MatchOutcome.Matched) service.match(req.requestId());
        assertThat(m.resource().resourceId()).isEqualTo(far.resourceId());
        assertThat(remaining(near)).isEqualByComparingTo("5");
        assertThat(remaining(far)).isEqualByComparingTo("480");
    }

    @Test
    void claimAttemptsAreCapped() {
        RequestDocument req = water(20);
        for (int i = 0; i < 8; i++) {
            waterResource(0.001 * (i + 1), "500");
        }
        // Every claim loses the race (another writer bumps the version each time).
        resources.beforeUpdate = id -> resources.concurrentWrite(resources.get(id));
        assertThat(service.match(req.requestId())).isInstanceOf(MatchOutcome.NoMatch.class);
        assertThat(requests.get(req.requestId()).status()).isEqualTo(RequestStatus.UNMATCHED);
    }

    @Test
    void cancelledWhileMatchingGivesTheCapacityBack() {
        RequestDocument req = water(20);
        ResourceDocument r = waterResource(0.001, "20"); // the claim will deplete it
        requests.beforeUpdate = id -> {
            requests.beforeUpdate = x -> { };
            requests.concurrentWrite(requests.get(id).withStatus(RequestStatus.CANCELLED, NOW));
        };

        MatchOutcome outcome = service.match(req.requestId());

        assertThat(outcome).isInstanceOf(MatchOutcome.NotMatchable.class);
        assertThat(requests.get(req.requestId()).status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(remaining(r)).as("capacity released").isEqualByComparingTo("20");
        assertThat(resources.get(r.resourceId()).status()).as("DEPLETED undone").isEqualTo(ResourceStatus.AVAILABLE);
    }

    @Test
    void requestChangedButStillMatchableIsRetried() {
        RequestDocument req = water(20);
        ResourceDocument r = waterResource(0.001, "500");
        requests.beforeUpdate = id -> {
            requests.beforeUpdate = x -> { };
            requests.concurrentWrite(requests.get(id)); // version bump only
        };
        MatchOutcome.Matched m = (MatchOutcome.Matched) service.match(req.requestId());
        assertThat(m.request().version()).isEqualTo(3);
        assertThat(remaining(r)).isEqualByComparingTo("480");
    }

    @Test
    void storageFailingAfterTheClaimGivesTheCapacityBackAndReportsIt() {
        RequestDocument req = water(20);
        ResourceDocument r = waterResource(0.001, "500");
        requests.updatesFail = true;
        assertThatThrownBy(() -> service.match(req.requestId())).isInstanceOf(DependencyUnavailableException.class);
        assertThat(remaining(r)).isEqualByComparingTo("500");
        assertThat(requests.get(req.requestId()).status()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void releaseNeverExceedsTotalAndKeepsOfflineOffline() {
        RequestDocument req = water(20);
        ResourceDocument r = waterResource(0.001, "500");
        requests.beforeUpdate = id -> {
            requests.beforeUpdate = x -> { };
            // Meanwhile: the request is cancelled and the provider takes the resource offline.
            requests.concurrentWrite(requests.get(id).withStatus(RequestStatus.CANCELLED, NOW));
            resources.concurrentWrite(resources.get(r.resourceId()).withStatus(ResourceStatus.OFFLINE, NOW));
        };
        service.match(req.requestId());
        assertThat(remaining(r)).isEqualByComparingTo("500");
        assertThat(resources.get(r.resourceId()).status()).isEqualTo(ResourceStatus.OFFLINE);
    }
}
