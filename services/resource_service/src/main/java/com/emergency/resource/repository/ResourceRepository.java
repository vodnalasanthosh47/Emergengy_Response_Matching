package com.emergency.resource.repository;

import java.util.List;
import java.util.Optional;

import com.emergency.common.model.Category;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;

public interface ResourceRepository {

    /** Stores a new resource; it must not exist yet. */
    StoredResource create(ResourceDocument resource);

    /** @param resourceId the client form {@code res_{geoCell}_{uuid}} */
    Optional<StoredResource> find(String resourceId);

    /**
     * Replaces the resource if it is still at {@code expectedVersion}.
     *
     * @throws com.emergency.resource.exception.ConcurrentUpdateException if it changed meanwhile
     * @throws com.emergency.resource.exception.ResourceNotFoundException  if it no longer exists
     */
    StoredResource update(ResourceDocument resource, long expectedVersion);

    /** Resources with the given type and status ({@code null} = any), sorted by key. */
    List<StoredResource> findAll(Category type, ResourceStatus status, int limit);
}
