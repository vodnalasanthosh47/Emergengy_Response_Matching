package com.emergency.matching.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.emergency.common.storage.StorageClient;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.support.FakeCoordinator;

import tools.jackson.databind.json.JsonMapper;

/** The lease against a coordinator (fake, over real HTTP) that enforces expectedVersion. */
class StorageSweepLeaseTest {

    private static final Duration TTL = Duration.ofMinutes(3);

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-21T09:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private FakeCoordinator coordinator;
    private StorageClient storage;
    private final TestClock clock = new TestClock();

    @BeforeEach
    void setUp() throws Exception {
        coordinator = new FakeCoordinator();
        storage = new StorageClient(coordinator.url(), Duration.ofSeconds(5), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    private StorageSweepLease pod(String name) {
        return new StorageSweepLease(storage, clock, name);
    }

    private String storedHolder() {
        return coordinator.record(StorageSweepLease.KEY).path("value").path("holder").stringValue();
    }

    @Test
    void theFirstPodTakesTheLeaseAsARecordWithItsCollection() {
        assertThat(pod("a").tryAcquire(TTL)).isTrue();
        assertThat(storedHolder()).isEqualTo("a");
        assertThat(coordinator.record(StorageSweepLease.KEY).path("indexes").path("collection").stringValue()).isEqualTo("lease");
        assertThat(coordinator.record(StorageSweepLease.KEY).path("value").path("expiresAt").stringValue())
                .isEqualTo("2026-11-21T09:03:00Z");
    }

    @Test
    void anotherPodIsRefusedWhileTheLeaseIsValid() {
        pod("a").tryAcquire(TTL);
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(pod("b").tryAcquire(TTL)).isFalse();
        assertThat(storedHolder()).isEqualTo("a");
    }

    @Test
    void theHolderRenewsAndKeepsIt() {
        StorageSweepLease a = pod("a");
        a.tryAcquire(TTL);
        for (int pass = 0; pass < 3; pass++) {
            clock.now = clock.now.plus(Duration.ofMinutes(2)); // every interval
            assertThat(a.tryAcquire(TTL)).isTrue();
            assertThat(pod("b").tryAcquire(TTL)).as("b never gets in").isFalse();
        }
        assertThat(coordinator.record(StorageSweepLease.KEY).path("version").longValue()).isEqualTo(4);
    }

    @Test
    void anotherPodTakesOverOnceTheHolderStopsRenewing() {
        pod("a").tryAcquire(TTL); // then "a" dies
        clock.now = clock.now.plus(TTL); // expired exactly now
        assertThat(pod("b").tryAcquire(TTL)).isTrue();
        assertThat(storedHolder()).isEqualTo("b");
    }

    /** Pods starting together: exactly one wins the create race. */
    @RepeatedTest(10)
    void racingPodsGetExactlyOneLease() throws Exception {
        int pods = 4;
        ExecutorService threads = Executors.newFixedThreadPool(pods);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < pods; i++) {
                StorageSweepLease lease = pod("pod-" + i);
                Callable<Boolean> race = () -> {
                    start.await();
                    return lease.tryAcquire(TTL);
                };
                results.add(threads.submit(race));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> r : results) {
                winners += r.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void storageDownIsReportedNotTreatedAsRefused() {
        coordinator.down = true;
        assertThatThrownBy(() -> pod("a").tryAcquire(TTL)).isInstanceOf(DependencyUnavailableException.class);
    }
}
