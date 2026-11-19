package com.emergency.matching.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
 */
@Validated
@ConfigurationProperties(prefix = "emergency")
public record MatchingServiceProperties(
        @NotBlank String coordinatorUrl,
        @NotNull Duration storageTimeout,
        @Min(1) @Max(1000) int candidateLimit,
        @Min(1) @Max(10) int eventAttempts,
        @NotNull Duration eventRetryDelay) {
}
