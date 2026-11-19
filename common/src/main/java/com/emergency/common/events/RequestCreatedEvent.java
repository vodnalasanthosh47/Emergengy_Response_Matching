package com.emergency.common.events;

import java.time.Instant;
import java.util.UUID;

import com.emergency.common.request.RequestView;

/**
 * Published after a request is stored (contracts/events/request-created.v1.json).
 *
 * <p>It carries the whole request (event-carried state transfer), but a consumer must
 * re-read the request from storage before acting: by then it may have been cancelled,
 * and a redelivered message must not cause a second match.
 */
public record RequestCreatedEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        RequestView request) {

    public static final String TYPE = "RequestCreated";
    public static final int SCHEMA_VERSION = 1;

    public static RequestCreatedEvent of(RequestView request, Instant now) {
        return new RequestCreatedEvent(UUID.randomUUID().toString(), TYPE, SCHEMA_VERSION, now, request);
    }
}
