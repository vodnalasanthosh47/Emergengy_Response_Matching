package com.emergency.common.request;

import java.time.Instant;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;

import tools.jackson.databind.JsonNode;

/**
 * A request as clients and events see it: the stored document plus its storage
 * {@code version} (spec §3.2; EmergencyRequest in contracts/openapi/request-service-api.yaml).
 */
public record RequestView(
        String requestId,
        Category type,
        Location location,
        String geoCell,
        RequestStatus status,
        Requester requester,
        JsonNode details,
        String assignedResourceId,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static RequestView of(RequestDocument d, long version) {
        return new RequestView(d.requestId(), d.type(), d.location(), d.geoCell(), d.status(), d.requester(),
                d.details(), d.assignedResourceId(), d.createdAt(), d.updatedAt(), version);
    }
}
