package com.emergency.resource.support;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.emergency.common.model.Category;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.resource.exception.ConcurrentUpdateException;
import com.emergency.resource.exception.DependencyUnavailableException;
import com.emergency.resource.exception.ResourceNotFoundException;
import com.emergency.resource.repository.ResourceRepository;
import com.emergency.resource.repository.StoredResource;

/** A test double honouring expectedVersion like the real storage. */
public class InMemoryResourceRepository implements ResourceRepository {

    public final Map<String, StoredResource> records = new ConcurrentHashMap<>();
    /** Runs just before an update is applied, to simulate a concurrent writer. */
    public Consumer<String> beforeUpdate = id -> { };
    public boolean down;
    public int updates;

    @Override
    public StoredResource create(ResourceDocument resource) {
        checkUp();
        StoredResource stored = new StoredResource(resource, 1);
        if (records.putIfAbsent(resource.resourceId(), stored) != null) {
            throw new IllegalStateException("exists");
        }
        return stored;
    }

    @Override
    public Optional<StoredResource> find(String resourceId) {
        checkUp();
        return Optional.ofNullable(records.get(resourceId));
    }

    @Override
    public StoredResource update(ResourceDocument resource, long expectedVersion) {
        checkUp();
        beforeUpdate.accept(resource.resourceId());
        StoredResource current = records.get(resource.resourceId());
        if (current == null) {
            throw new ResourceNotFoundException("gone");
        }
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdateException("conflict", null);
        }
        updates++;
        StoredResource next = new StoredResource(resource, expectedVersion + 1);
        records.put(resource.resourceId(), next);
        return next;
    }

    @Override
    public List<StoredResource> findAll(Category type, ResourceStatus status, int limit) {
        checkUp();
        return records.values().stream()
                .filter(r -> type == null || r.document().type() == type)
                .filter(r -> status == null || r.document().status() == status)
                .sorted(Comparator.comparing(r -> r.document().resourceId()))
                .limit(limit)
                .toList();
    }

    /** Puts a copy of {@code resource} at {@code version}, as another writer would. */
    public void put(ResourceDocument resource, long version) {
        records.put(resource.resourceId(), new StoredResource(resource, version));
    }

    private void checkUp() {
        if (down) {
            throw new DependencyUnavailableException("storage down", null);
        }
    }
}
