package com.emergency.common.resource;

import java.time.Instant;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;

import tools.jackson.databind.JsonNode;

/**
 * A resource as stored: the {@code value} of the record under
 * {@code resource:{geoCell}:{uuid}} (docs/data-format.md §6). It has no version; the
 * storage record owns that, and {@link ResourceView} adds it for responses.
 *
 * @param resourceId client-facing ID, {@code res_{geoCell}_{uuid}}
 * @param details    MEDICINE: {@code {"name": ...}}; other types: {@code {}}
 */
public record ResourceDocument(
        String resourceId,
        Category type,
        Provider provider,
        Location location,
        String geoCell,
        Capacity capacity,
        JsonNode details,
        ResourceStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public ResourceDocument withStatus(ResourceStatus newStatus, Instant now) {
        return new ResourceDocument(resourceId, type, provider, location, geoCell, capacity, details, newStatus,
                createdAt, now);
    }
}
