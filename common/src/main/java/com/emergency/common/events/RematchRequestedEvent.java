package com.emergency.common.events;

import java.time.Instant;
import java.util.UUID;

import com.emergency.common.model.Category;

/**
 * Asks the Matching Service to try a waiting request again
 * (contracts/events/rematch-requested.v1.json). Published by the Matching Service's own
 * rematch sweeper onto the same queue as {@link RequestCreatedEvent}.
 *
 * <p>Unlike RequestCreated it carries only the request's ID (and its type, for the
 * routing key): the consumer must re-read the request from storage before acting anyway.
 */
public record RematchRequestedEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String requestId,
        Category type,
        Reason reason) {

    public static final String TYPE = "RematchRequested";
    public static final int SCHEMA_VERSION = 1;

    /** Why the request is being re-queued. */
    public enum Reason {
        /** No resource was found earlier; one may exist now. */
        UNMATCHED,
        /** Still PENDING after a while: its RequestCreated event was probably never delivered. */
        STALE_PENDING
    }

    public static RematchRequestedEvent of(String requestId, Category type, Reason reason, Instant now) {
        return new RematchRequestedEvent(UUID.randomUUID().toString(), TYPE, SCHEMA_VERSION, now, requestId, type, reason);
    }
}
