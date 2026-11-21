package com.emergency.matching.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Settings under {@code emergency.*}, filled from environment variables and validated at
 * startup.
 *
 * @param coordinatorUrl   storage coordinator, e.g. http://coordinator:8080
 * @param storageTimeout   bound on each storage call
 * @param candidateLimit   how many AVAILABLE resources to fetch from the 9 search cells
 * @param eventAttempts    how often a RequestCreated event is tried while storage is
 *                         unavailable, before it goes to the dead-letter queue
 * @param eventRetryDelay  pause between those attempts
 * @param rematch          the rematch sweeper (see {@link Rematch})
 */
@Validated
@ConfigurationProperties(prefix = "emergency")
public record MatchingServiceProperties(
        @NotBlank String coordinatorUrl,
        @NotNull Duration storageTimeout,
        @Min(1) @Max(1000) int candidateLimit,
        @Min(1) @Max(10) int eventAttempts,
        @NotNull Duration eventRetryDelay,
        @NotNull @Valid Rematch rematch) {

    /**
     * The rematch sweeper, which re-queues requests still waiting for a resource
     * ({@code emergency.rematch.*}).
     *
     * @param enabled    REMATCH_ENABLED: run the sweeper at all
     * @param interval   REMATCH_INTERVAL: pause between two passes (e.g. 2m), measured
     *                   from the end of one pass, so passes never overlap
     * @param pendingAge REMATCH_PENDING_AGE: a PENDING request unchanged for this long is
     *                   re-queued too (its RequestCreated event was probably lost)
     * @param batchSize  REMATCH_BATCH_SIZE: most requests re-queued in one pass
     */
    public record Rematch(
            boolean enabled,
            @NotNull Duration interval,
            @NotNull Duration pendingAge,
            @Min(1) @Max(1000) int batchSize) {
    }
}
