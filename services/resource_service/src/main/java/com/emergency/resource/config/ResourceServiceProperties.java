package com.emergency.resource.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Settings under {@code emergency.*}, filled from environment variables. Validated at
 * startup so a bad value stops the service instead of failing on the first request.
 *
 * @param geohashPrecision must equal the Request Service's, or matching cannot find resources
 */
@Validated
@ConfigurationProperties(prefix = "emergency")
public record ResourceServiceProperties(
        @NotBlank String coordinatorUrl,
        @NotNull Duration storageTimeout,
        @Min(1) @Max(12) int geohashPrecision) {
}
