package com.emergency.request.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Settings under {@code emergency.*} in application.yml, filled from environment
 * variables (COORDINATOR_URL, GEOHASH_PRECISION, ...). Validated at startup, so a bad
 * value stops the service instead of failing on the first request.
 *
 * @param coordinatorUrl        storage coordinator, e.g. http://coordinator:8080
 * @param storageTimeout        bound on each storage call (above the coordinator's own 2 × 3 s)
 * @param geohashPrecision      cell size; must be the same in every service (spec §3.4)
 * @param publishConfirmTimeout how long to wait for RabbitMQ to confirm an event
 */
@Validated
@ConfigurationProperties(prefix = "emergency")
public record RequestServiceProperties(
        @NotBlank String coordinatorUrl,
        @NotNull Duration storageTimeout,
        @Min(1) @Max(12) int geohashPrecision,
        @NotNull Duration publishConfirmTimeout) {
}
