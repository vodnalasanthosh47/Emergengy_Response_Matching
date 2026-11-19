package com.emergency.matching.domain;

import java.util.List;
import java.util.Optional;

import com.emergency.common.model.Category;
import com.emergency.common.resource.ResourceDocument;

/** Resources in storage, as matching needs them. Implemented on the coordinator in the storage package. */
public interface ResourceStore {

    /** AVAILABLE resources of {@code type} in any of {@code cells} (at most {@code limit}). */
    List<Versioned<ResourceDocument>> findAvailable(Category type, List<String> cells, int limit);

    /** @param resourceId client form {@code res_{cell}_{uuid}} */
    Optional<Versioned<ResourceDocument>> find(String resourceId);

    /**
     * Replaces the resource if it is still at {@code expectedVersion}.
     *
     * @throws ConcurrentUpdateException if someone else changed it first (e.g. another claim)
     */
    Versioned<ResourceDocument> update(ResourceDocument resource, long expectedVersion);
}
