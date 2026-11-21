package com.emergency.matching.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emergency.common.events.RematchRequestedEvent.Reason;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;

/**
 * The safety net for requests that are waiting for a resource. Run periodically
 * ({@code REMATCH_INTERVAL}), it puts them back on the matching queue:
 *
 * <ul>
 * <li><b>UNMATCHED</b> requests: nothing fitted when they were matched, but a resource may
 *     have been registered, come back online or had capacity returned since.</li>
 * <li><b>PENDING</b> requests not changed for {@code pendingAge}: their RequestCreated
 *     event was probably never delivered (e.g. the Request Service crashed between
 *     storing and publishing), or a client set an UNMATCHED request back to PENDING.</li>
 * </ul>
 *
 * This is a reconciliation loop: instead of reacting to every event that could make a
 * match possible, it regularly re-checks the state and fixes what is left over. Each
 * request goes through the normal consumer, which re-reads it and matches it only if it
 * is still PENDING or UNMATCHED, so re-queuing is always safe: a request with still
 * nothing nearby stays UNMATCHED (no write), and one matched meanwhile is left alone.
 *
 * <p>Oldest requests go first, at most {@code batchSize} per pass, so a backlog cannot
 * flood the queue in one burst; the rest are picked up by the next passes.
 *
 * <p>With several matching pods, only the holder of the {@link SweepLease} sweeps; the
 * others skip the pass.
 */
public class RematchSweeper {

    private static final Logger log = LoggerFactory.getLogger(RematchSweeper.class);

    /** Requests read per status in one pass: the coordinator's largest query limit. */
    static final int FETCH_LIMIT = 1000;

    private final WaitingRequests waiting;
    private final RematchPublisher publisher;
    private final SweepLease lease;
    private final Duration leaseTtl;
    private final Clock clock;
    private final Duration pendingAge;
    private final int batchSize;

    /**
     * @param leaseTtl how long a pass holds the lease; longer than the interval between
     *                 passes, so the holder renews it before it expires and keeps it
     */
    public RematchSweeper(WaitingRequests waiting, RematchPublisher publisher, SweepLease lease, Duration leaseTtl,
            Clock clock, Duration pendingAge, int batchSize) {
        if (pendingAge.isNegative() || batchSize < 1 || !leaseTtl.isPositive()) {
            throw new IllegalArgumentException("pendingAge must be >= 0, batchSize >= 1 and leaseTtl > 0");
        }
        this.waiting = waiting;
        this.publisher = publisher;
        this.lease = lease;
        this.leaseTtl = leaseTtl;
        this.clock = clock;
        this.pendingAge = pendingAge;
        this.batchSize = batchSize;
    }

    /**
     * What one pass found and how many requests it re-queued.
     *
     * @param ran false if another instance holds the lease (nothing was done)
     */
    public record Result(boolean ran, int unmatched, int stalePending, int published) {

        static Result skipped() {
            return new Result(false, 0, 0, 0);
        }
    }

    private record Due(RequestDocument request, Reason reason) {
    }

    /**
     * Runs one pass.
     *
     * @throws DependencyUnavailableException if storage cannot be reached (nothing was
     *         published; the next pass tries again)
     */
    public Result sweep() {
        if (!lease.tryAcquire(leaseTtl)) {
            log.debug("rematch: another instance holds the sweep lease; skipping this pass");
            return Result.skipped();
        }
        Instant cutoff = clock.instant().minus(pendingAge);
        List<RequestDocument> unmatched = waiting.withStatus(RequestStatus.UNMATCHED, FETCH_LIMIT);
        List<RequestDocument> stalePending = waiting.withStatus(RequestStatus.PENDING, FETCH_LIMIT).stream()
                .filter(r -> r.updatedAt() == null || !r.updatedAt().isAfter(cutoff))
                .toList();

        List<Due> due = Stream.concat(
                        unmatched.stream().map(r -> new Due(r, Reason.UNMATCHED)),
                        stalePending.stream().map(r -> new Due(r, Reason.STALE_PENDING)))
                .sorted(Comparator.comparing((Due d) -> d.request().createdAt(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(d -> d.request().requestId()))
                .limit(batchSize)
                .toList();

        int published = 0;
        for (Due d : due) {
            try {
                publisher.publish(d.request().requestId(), d.request().type(), d.reason());
                published++;
            } catch (DependencyUnavailableException e) {
                // The broker is down: the rest would fail too. They are still waiting in
                // storage, so the next pass re-queues them.
                log.warn("rematch: message broker unavailable after {} of {} requests: {}", published, due.size(),
                        e.getMessage());
                break;
            }
        }
        if (!due.isEmpty()) {
            log.info("rematch: re-queued {} of {} waiting requests ({} UNMATCHED, {} PENDING for over {})", published,
                    unmatched.size() + stalePending.size(), unmatched.size(), stalePending.size(), pendingAge);
        }
        return new Result(true, unmatched.size(), stalePending.size(), published);
    }
}
