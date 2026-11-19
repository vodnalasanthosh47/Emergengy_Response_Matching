package com.emergency.resource.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.emergency.common.model.Category;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.InvalidStorageRequestException;
import com.emergency.common.storage.PreconditionFailedException;
import com.emergency.common.storage.StorageClient;
import com.emergency.common.storage.StorageKeys;
import com.emergency.common.storage.StorageQuery;
import com.emergency.common.storage.StorageRecord;
import com.emergency.common.storage.StorageUnavailableException;
import com.emergency.common.storage.VersionConflictException;
import com.emergency.resource.exception.ConcurrentUpdateException;
import com.emergency.resource.exception.DependencyUnavailableException;
import com.emergency.resource.exception.ResourceNotFoundException;

/**
 * Resources stored through the coordinator (docs/data-format.md §6):
 *
 * <pre>
 * key      resource:{geoCell}:{uuid}                  (shard = crc32(geoCell) mod 3)
 * value    the ResourceDocument
 * indexes  collection=resource, type, status, geoCell (geoCell = the key's cell)
 * </pre>
 */
@Repository
public class CoordinatorResourceRepository implements ResourceRepository {

    private final StorageClient storage;

    public CoordinatorResourceRepository(StorageClient storage) {
        this.storage = storage;
    }

    @Override
    public StoredResource create(ResourceDocument resource) {
        String key = StorageKeys.resourceIdToKey(resource.resourceId());
        try {
            return toStored(storage.create(key, resource, indexes(resource)));
        } catch (VersionConflictException e) {
            throw new IllegalStateException("resource id collision: " + resource.resourceId(), e);
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        } catch (InvalidStorageRequestException e) {
            throw new IllegalStateException("storage rejected a resource record (bug): " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StoredResource> find(String resourceId) {
        try {
            return storage.get(StorageKeys.resourceIdToKey(resourceId)).map(this::toStored);
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        }
    }

    @Override
    public StoredResource update(ResourceDocument resource, long expectedVersion) {
        String key = StorageKeys.resourceIdToKey(resource.resourceId());
        try {
            return toStored(storage.replace(key, resource, indexes(resource), expectedVersion));
        } catch (VersionConflictException e) {
            throw new ConcurrentUpdateException("resource " + resource.resourceId() + " changed meanwhile", e);
        } catch (PreconditionFailedException e) {
            throw new ResourceNotFoundException("no resource with id " + resource.resourceId());
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        } catch (InvalidStorageRequestException e) {
            throw new IllegalStateException("storage rejected a resource record (bug): " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredResource> findAll(Category type, ResourceStatus status, int limit) {
        Map<String, String> match = new HashMap<>();
        if (type != null) {
            match.put("type", type.name());
        }
        if (status != null) {
            match.put("status", status.name());
        }
        try {
            return storage.query(new StorageQuery(StorageKeys.RESOURCE, List.of(), match, limit)).stream()
                    .map(this::toStored)
                    .toList();
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        }
    }

    /** The searchable copy of some value fields; must be written together with the value. */
    static Map<String, String> indexes(ResourceDocument r) {
        return Map.of(
                "collection", StorageKeys.RESOURCE,
                "type", r.type().name(),
                "status", r.status().name(),
                "geoCell", r.geoCell());
    }

    private StoredResource toStored(StorageRecord record) {
        return new StoredResource(storage.valueAs(record, ResourceDocument.class), record.version());
    }

    private static DependencyUnavailableException unavailable(StorageUnavailableException e) {
        return new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
    }
}
