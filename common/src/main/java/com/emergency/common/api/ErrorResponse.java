package com.emergency.common.api;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The one error shape every public API returns (spec §4.1,
 * contracts/openapi/common.yaml#/components/schemas/Error).
 */
public record ErrorResponse(String error, String message, String timestamp) {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";     // 400
    public static final String NOT_FOUND = "NOT_FOUND";                 // 404
    public static final String CONFLICT = "CONFLICT";                   // 409
    public static final String NO_MATCH = "NO_MATCH";                   // 422 (matching)
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"; // 503
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";       // 500

    public static ErrorResponse of(String error, String message, Clock clock) {
        return new ErrorResponse(error, message, Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
