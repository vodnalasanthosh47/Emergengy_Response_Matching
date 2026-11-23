package com.emergency.matching.domain;

import java.time.Duration;

/**
 * Decides which matching instance runs the rematch sweep, so that with several pods only
 * one re-queues the waiting requests per interval. (Without it every pod would sweep:
 * harmless, because re-queuing is idempotent, but repeated work.)
 */
public interface SweepLease {

    /**
     * Takes or renews the lease for {@code ttl}. Returns false if another instance holds a
     * lease that has not expired, or won the race for it.
     *
     * @throws DependencyUnavailableException if storage cannot be reached
     */
    boolean tryAcquire(Duration ttl);
}
