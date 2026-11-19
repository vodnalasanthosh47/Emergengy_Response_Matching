package com.emergency.common.resource;

import java.time.Instant;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;

import tools.jackson.databind.JsonNode;

/** A resource as clients see it: the stored document plus its storage {@code version}. */
public record ResourceView(
        String resourceId,
        Category type,
        Provider provider,
        Location location,
        String geoCell,
        Capacity capacity,
        JsonNode details,
        ResourceStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static ResourceView of(ResourceDocument d, long version) {
        return new ResourceView(d.resourceId(), d.type(), d.provider(), d.location(), d.geoCell(), d.capacity(),
                d.details(), d.status(), d.createdAt(), d.updatedAt(), version);
    }
}
