package com.emergency.matching.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.emergency.common.storage.PreconditionFailedException;
import com.emergency.common.storage.StorageClient;
import com.emergency.common.storage.StorageRecord;
import com.emergency.common.storage.StorageUnavailableException;
import com.emergency.common.storage.VersionConflictException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.SweepLease;

/**
 * A lease kept as one storage record, {@code lease:rematch-sweeper} =
 * {@code {holder, expiresAt}}, and taken with the same compare-and-set as every claim:
 *
 * <ul>
 * <li>no record → create it ({@code expectedVersion 0});</li>
 * <li>held by us, or expired → replace it on the version just read;</li>
 * <li>held by another instance and not expired → refuse.</li>
 * </ul>
 *
 * Two instances that race both read version v; only one write on v succeeds (the other
 * gets 409), so only one holds the lease. The holder renews it on every pass, so it keeps
 * it while alive; if it dies, another instance takes over once the lease expires.
 *
 * <p>Expiry compares the holder's clock with ours: a few seconds of clock skew between
 * pods can at worst let two instances sweep once at the same time, which is harmless.
 */
public class StorageSweepLease implements SweepLease {

    public static final String KEY = "lease:rematch-sweeper";
    /** {@code collection} is the only index the coordinator requires. */
    static final Map<String, String> INDEXES = Map.of("collection", "lease");

    /** The stored value. */
    public record Lease(String holder, Instant expiresAt) {
    }

    private final StorageClient storage;
    private final Clock clock;
    private final String holder;

    /** @param holder unique per instance (process), e.g. pod name + random suffix */
    public StorageSweepLease(StorageClient storage, Clock clock, String holder) {
        this.storage = storage;
        this.clock = clock;
        this.holder = holder;
    }

    public String holder() {
        return holder;
    }

    @Override
    public boolean tryAcquire(Duration ttl) {
        Instant now = clock.instant();
        Lease mine = new Lease(holder, now.plus(ttl));
        try {
            Optional<StorageRecord> current = storage.get(KEY);
            if (current.isEmpty()) {
                storage.create(KEY, mine, INDEXES);
                return true;
            }
            Lease held = storage.valueAs(current.get(), Lease.class);
            if (!holder.equals(held.holder()) && held.expiresAt().isAfter(now)) {
                return false; // another instance is sweeping
            }
            storage.replace(KEY, mine, INDEXES, current.get().version());
            return true;
        } catch (VersionConflictException | PreconditionFailedException e) {
            return false; // another instance won the race
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }
}
