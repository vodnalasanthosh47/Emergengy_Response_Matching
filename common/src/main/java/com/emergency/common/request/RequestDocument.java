package com.emergency.common.request;

import java.time.Instant;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;

import tools.jackson.databind.JsonNode;

/**
 * A request as stored: the {@code value} of the storage record under
 * {@code request:{requestId}} (docs/data-format.md §5).
 *
 * <p>It has no {@code version}: the version belongs to the storage record, which changes
 * it on every write. {@link RequestView} adds it for API responses and events.
 *
 * @param details type-specific details, already validated and normalised by the Request
 *                Service (shape per type in contracts/openapi/request-service-api.yaml)
 */
public record RequestDocument(
        String requestId,
        Category type,
        Location location,
        String geoCell,
        RequestStatus status,
        Requester requester,
        JsonNode details,
        String assignedResourceId,
        Instant createdAt,
        Instant updatedAt) {

    /** A copy with a new status, stamped with {@code now}. */
    public RequestDocument withStatus(RequestStatus newStatus, Instant now) {
        return new RequestDocument(requestId, type, location, geoCell, newStatus, requester, details,
                assignedResourceId, createdAt, now);
    }

    /**
     * A copy that is MATCHED to {@code resourceId} (client form {@code res_{cell}_{uuid}}).
     * Status and assignment change together, in one write, so a MATCHED request always
     * names its resource. Used by the Matching Service.
     */
    public RequestDocument matchedTo(String resourceId, Instant now) {
        return new RequestDocument(requestId, type, location, geoCell, RequestStatus.MATCHED, requester, details,
                resourceId, createdAt, now);
    }
}
