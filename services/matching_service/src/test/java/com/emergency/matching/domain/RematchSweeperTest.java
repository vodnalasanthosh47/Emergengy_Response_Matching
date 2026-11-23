package com.emergency.matching.domain;

import static com.emergency.matching.support.Fixtures.LAT;
import static com.emergency.matching.support.Fixtures.LNG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emergency.common.events.RematchRequestedEvent.Reason;
import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.matching.support.Fixtures;

class RematchSweeperTest {

    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");
    private static final Duration PENDING_AGE = Duration.ofMinutes(2);

    /** Requests by status, as storage would return them. */
    private final Map<RequestStatus, List<RequestDocument>> stored = new EnumMap<>(RequestStatus.class);
    private boolean storageDown;
    private final WaitingRequests waiting = (status, limit) -> {
        if (storageDown) {
            throw new DependencyUnavailableException("storage down");
        }
        return stored.getOrDefault(status, List.of()).stream().limit(limit).toList();
    };

    private record Published(String requestId, Category type, Reason reason) {
    }

    private final List<Published> published = new ArrayList<>();
    /** The broker fails from this publish on (-1 = never). */
    private int brokerFailsAt = -1;
    private final RematchPublisher publisher = (id, type, reason) -> {
        if (published.size() == brokerFailsAt) {
            throw new DependencyUnavailableException("broker down");
        }
        published.add(new Published(id, type, reason));
    };

    private boolean leaseHeldElsewhere;
    private final SweepLease lease = ttl -> !leaseHeldElsewhere;

    private RematchSweeper sweeper(int batchSize) {
        return new RematchSweeper(waiting, publisher, lease, Duration.ofMinutes(3), Clock.fixed(NOW, ZoneOffset.UTC),
                PENDING_AGE, batchSize);
    }

    /** A stored request whose createdAt and updatedAt are {@code age} before now. */
    private RequestDocument stored(RequestStatus status, Duration age) {
        Instant at = NOW.minus(age);
        RequestDocument r = Fixtures.request(java.util.UUID.randomUUID().toString(), Category.WATER, LAT, LNG,
                "{\"quantity\":20,\"unit\":\"L\"}", status);
        r = new RequestDocument(r.requestId(), r.type(), r.location(), r.geoCell(), status, null, r.details(), null, at, at);
        stored.computeIfAbsent(status, s -> new ArrayList<>()).add(r);
        return r;
    }

    @Test
    void requeuesUnmatchedAndStalePendingButNotFreshPending() {
        RequestDocument unmatched = stored(RequestStatus.UNMATCHED, Duration.ofSeconds(10));
        RequestDocument stale = stored(RequestStatus.PENDING, Duration.ofMinutes(5));
        stored(RequestStatus.PENDING, Duration.ofSeconds(30)); // its RequestCreated is probably still on its way

        RematchSweeper.Result result = sweeper(500).sweep();

        assertThat(result).isEqualTo(new RematchSweeper.Result(true, 1, 1, 2));
        assertThat(published).containsExactlyInAnyOrder(
                new Published(unmatched.requestId(), Category.WATER, Reason.UNMATCHED),
                new Published(stale.requestId(), Category.WATER, Reason.STALE_PENDING));
    }

    @Test
    void pendingExactlyAtTheAgeLimitIsStale() {
        RequestDocument r = stored(RequestStatus.PENDING, PENDING_AGE);
        sweeper(500).sweep();
        assertThat(published).extracting(Published::requestId).containsExactly(r.requestId());
    }

    @Test
    void oldestRequestsGoFirstAndAPassIsCapped() {
        RequestDocument newest = stored(RequestStatus.UNMATCHED, Duration.ofMinutes(1));
        RequestDocument oldest = stored(RequestStatus.PENDING, Duration.ofHours(3));
        RequestDocument middle = stored(RequestStatus.UNMATCHED, Duration.ofHours(1));

        RematchSweeper.Result result = sweeper(2).sweep();

        assertThat(published).extracting(Published::requestId).containsExactly(oldest.requestId(), middle.requestId());
        assertThat(result.published()).isEqualTo(2);
        assertThat(published).extracting(Published::requestId).doesNotContain(newest.requestId());
    }

    @Test
    void brokerFailureStopsThePassWithoutError() {
        stored(RequestStatus.UNMATCHED, Duration.ofHours(3));
        stored(RequestStatus.UNMATCHED, Duration.ofHours(2));
        stored(RequestStatus.UNMATCHED, Duration.ofHours(1));
        brokerFailsAt = 1;

        RematchSweeper.Result result = sweeper(500).sweep();

        assertThat(result.published()).as("the others wait for the next pass").isEqualTo(1);
    }

    @Test
    void storageFailurePublishesNothing() {
        stored(RequestStatus.UNMATCHED, Duration.ofHours(1));
        storageDown = true;
        assertThatThrownBy(() -> sweeper(500).sweep()).isInstanceOf(DependencyUnavailableException.class);
        assertThat(published).isEmpty();
    }

    @Test
    void nothingWaitingPublishesNothing() {
        stored(RequestStatus.MATCHED, Duration.ofHours(1)); // never asked for
        assertThat(sweeper(500).sweep()).isEqualTo(new RematchSweeper.Result(true, 0, 0, 0));
        assertThat(published).isEmpty();
    }

    @Test
    void anotherInstanceHoldingTheLeaseMeansNoWork() {
        stored(RequestStatus.UNMATCHED, Duration.ofHours(1));
        leaseHeldElsewhere = true;
        storageDown = true; // proves storage is not even read

        assertThat(sweeper(500).sweep()).isEqualTo(new RematchSweeper.Result(false, 0, 0, 0));
        assertThat(published).isEmpty();
    }

    @Test
    void rejectsNonsenseSettings() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Duration ttl = Duration.ofMinutes(3);
        assertThatThrownBy(() -> new RematchSweeper(waiting, publisher, lease, ttl, clock, PENDING_AGE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RematchSweeper(waiting, publisher, lease, ttl, clock, Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RematchSweeper(waiting, publisher, lease, Duration.ZERO, clock, PENDING_AGE, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
