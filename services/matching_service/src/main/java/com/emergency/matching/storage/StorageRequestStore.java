package com.emergency.matching.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.storage.PreconditionFailedException;
import com.emergency.common.storage.StorageClient;
import com.emergency.common.storage.StorageKeys;
import com.emergency.common.storage.StorageQuery;
import com.emergency.common.storage.StorageRecord;
import com.emergency.common.storage.StorageUnavailableException;
import com.emergency.common.storage.VersionConflictException;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.RequestStore;
import com.emergency.matching.domain.Versioned;
import com.emergency.matching.domain.WaitingRequests;

/**
 * Requests through the coordinator, in the Request Service's format (docs/data-format.md §5):
 * key {@code request:{requestId}}, indexes collection/type/status/geoCell. The status
 * index is rewritten with every write, so it always matches the value.
 */
public class StorageRequestStore implements RequestStore, WaitingRequests {

    private final StorageClient storage;

    public StorageRequestStore(StorageClient storage) {
        this.storage = storage;
    }

    @Override
    public Optional<Versioned<RequestDocument>> find(String requestId) {
        try {
            return storage.get(StorageKeys.request(requestId)).map(this::toVersioned);
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public Versioned<RequestDocument> update(RequestDocument request, long expectedVersion) {
        try {
            return toVersioned(storage.replace(StorageKeys.request(request.requestId()), request, indexes(request),
                    expectedVersion));
        } catch (VersionConflictException | PreconditionFailedException e) {
            // Changed, or deleted, since we read it: the domain re-reads and decides again.
            throw new ConcurrentUpdateException("request " + request.requestId() + " changed meanwhile", e);
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * {@code POST /v1/query {collection: request, match: {status}}}. Requests are sharded
     * by ID, not by cell, so the coordinator asks all three shards.
     */
    @Override
    public List<RequestDocument> withStatus(RequestStatus status, int limit) {
        try {
            return storage.query(new StorageQuery(StorageKeys.REQUEST, List.of(), Map.of("status", status.name()), limit))
                    .stream()
                    .map(r -> storage.valueAs(r, RequestDocument.class))
                    .toList();
        } catch (StorageUnavailableException e) {
            throw new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
        }
    }

    /** Same index attributes as the Request Service writes. */
    static Map<String, String> indexes(RequestDocument r) {
        return Map.of(
                "collection", StorageKeys.REQUEST,
                "type", r.type().name(),
                "status", r.status().name(),
                "geoCell", r.geoCell());
    }

    private Versioned<RequestDocument> toVersioned(StorageRecord record) {
        return new Versioned<>(storage.valueAs(record, RequestDocument.class), record.version());
    }
}
