package com.emergency.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.matching.support.Fixtures;
import com.emergency.matching.support.InMemoryStores;

/**
 * The CP1 Definition of Done, with real threads against stores that behave like storage
 * (compare-and-set on the version). Repeated, because a race only shows up sometimes.
 */
class MatchingConcurrencyTest {

    private final InMemoryStores.Requests requests = new InMemoryStores.Requests();
    private final InMemoryStores.Resources resources = new InMemoryStores.Resources();
    private final MatchingService service =
            new MatchingService(requests, resources, new CandidateRanking(), Clock.systemUTC(), 100);

    /** Runs all tasks at the same moment and returns their results. */
    private static List<MatchOutcome> together(List<Callable<MatchOutcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MatchOutcome>> futures = new ArrayList<>();
            for (Callable<MatchOutcome> t : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return t.call();
                }));
            }
            start.countDown();
            List<MatchOutcome> out = new ArrayList<>();
            for (Future<MatchOutcome> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** DoD: two simultaneous requests, one resource that can serve only one → one MATCHED, one UNMATCHED. */
    @RepeatedTest(50)
    void twoRequestsOneResourceNeverDoubleBooks() throws Exception {
        RequestDocument a = Fixtures.request(Category.WATER, "{\"quantity\":20,\"unit\":\"L\"}");
        RequestDocument b = Fixtures.request(Category.WATER, "{\"quantity\":20,\"unit\":\"L\"}");
        requests.put(a);
        requests.put(b);
        ResourceDocument only = Fixtures.resource(Category.WATER, 0.001, "30", "30");
        resources.put(only);

        together(List.of(() -> service.match(a.requestId()), () -> service.match(b.requestId())));

        List<RequestStatus> statuses = List.of(requests.get(a.requestId()).status(), requests.get(b.requestId()).status());
        assertThat(statuses).containsExactlyInAnyOrder(RequestStatus.MATCHED, RequestStatus.UNMATCHED);
        assertThat(resources.get(only.resourceId()).capacity().remaining()).isEqualByComparingTo("10");
    }

    /** Ten requests race for a resource that can serve exactly three. */
    @RepeatedTest(20)
    void manyRequestsNeverOverdrawCapacity() throws Exception {
        ResourceDocument r = Fixtures.resource(Category.SHELTER, 0.001, "30", "30");
        resources.put(r);
        List<RequestDocument> reqs = new ArrayList<>();
        List<Callable<MatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RequestDocument q = Fixtures.request(Category.SHELTER, "{\"people\":10}");
            requests.put(q);
            reqs.add(q);
            tasks.add(() -> service.match(q.requestId()));
        }
        together(tasks);

        long matched = reqs.stream().filter(q -> requests.get(q.requestId()).status() == RequestStatus.MATCHED).count();
        BigDecimal remaining = resources.get(r.resourceId()).capacity().remaining();
        assertThat(matched).isEqualTo(3);
        assertThat(remaining).isEqualByComparingTo("0");
        assertThat(resources.get(r.resourceId()).status()).isEqualTo(ResourceStatus.DEPLETED);
        assertThat(reqs.stream().map(q -> requests.get(q.requestId()).status()))
                .containsOnly(RequestStatus.MATCHED, RequestStatus.UNMATCHED);
    }

    /** DoD: the same event delivered twice at once (redelivery + POST /match) claims only once. */
    @RepeatedTest(50)
    void theSameRequestMatchedTwiceAtOnceClaimsOnce() throws Exception {
        RequestDocument q = Fixtures.request(Category.WATER, "{\"quantity\":20,\"unit\":\"L\"}");
        requests.put(q);
        ResourceDocument r1 = Fixtures.resource(Category.WATER, 0.001, "500", "500");
        ResourceDocument r2 = Fixtures.resource(Category.WATER, 0.002, "500", "500");
        resources.put(r1);
        resources.put(r2);

        together(List.of(() -> service.match(q.requestId()), () -> service.match(q.requestId())));

        BigDecimal taken = new BigDecimal("1000")
                .subtract(resources.get(r1.resourceId()).capacity().remaining())
                .subtract(resources.get(r2.resourceId()).capacity().remaining());
        assertThat(taken).as("exactly one claim of 20 L in total").isEqualByComparingTo("20");
        RequestDocument stored = requests.get(q.requestId());
        assertThat(stored.status()).isEqualTo(RequestStatus.MATCHED);
        assertThat(resources.get(stored.assignedResourceId()).capacity().remaining()).isEqualByComparingTo("480");
    }
}
