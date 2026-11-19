package com.emergency.matching.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.emergency.common.model.Category;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.PreconditionFailedException;
import com.emergency.common.storage.StorageClient;
import com.emergency.common.storage.StorageKeys;
import com.emergency.common.storage.StorageQuery;
import com.emergency.common.storage.StorageRecord;
import com.emergency.common.storage.StorageUnavailableException;
import com.emergency.common.storage.VersionConflictException;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.ResourceStore;
import com.emergency.matching.domain.Versioned;

/**
 * Resources through the coordinator, in the Resource Service's format (docs/data-format.md §6):
 * key {@code resource:{geoCell}:{uuid}}, indexes collection/type/status/geoCell.
 *
 * The candidate search sends the 9 cells as {@code geoCells}, so the coordinator asks only
 * the shards that own those cells (resources are sharded by cell).
 */
public class StorageResourceStore implements ResourceStore {

    private final StorageClient storage;

    public StorageResourceStore(StorageClient storage) {
        this.storage = storage;
    }

    @Override
    public List<Versioned<ResourceDocument>> findAvailable(Category type, List<String> cells, int limit) {
        StorageQuery query = new StorageQuery(StorageKeys.RESOURCE, cells,
                Map.of("type", type.name(), "status", ResourceStatus.AVAILABLE.name()), limit);
        try {
            return storage.query(query).stream().map(this::toVersioned).toList();
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Versioned<ResourceDocument>> find(String resourceId) {
        try {
            return storage.get(StorageKeys.resourceIdToKey(resourceId)).map(this::toVersioned);
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public Versioned<ResourceDocument> update(ResourceDocument resource, long expectedVersion) {
        try {
            return toVersioned(storage.replace(StorageKeys.resourceIdToKey(resource.resourceId()), resource,
                    indexes(resource), expectedVersion));
        } catch (VersionConflictException | PreconditionFailedException e) {
            throw new ConcurrentUpdateException("resource " + resource.resourceId() + " changed meanwhile", e);
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }

    /** Same index attributes as the Resource Service writes; status follows every claim and release. */
    static Map<String, String> indexes(ResourceDocument r) {
        return Map.of(
                "collection", StorageKeys.RESOURCE,
                "type", r.type().name(),
                "status", r.status().name(),
                "geoCell", r.geoCell());
    }

    private Versioned<ResourceDocument> toVersioned(StorageRecord record) {
        return new Versioned<>(storage.valueAs(record, ResourceDocument.class), record.version());
    }
}
