package com.emergency.request.storage;

import java.util.Map;
import java.util.Optional;

import com.emergency.common.request.RequestDocument;
import com.emergency.common.storage.InvalidStorageRequestException;
import com.emergency.common.storage.PreconditionFailedException;
import com.emergency.common.storage.RecordNotFoundException;
import com.emergency.common.storage.StorageClient;
import com.emergency.common.storage.StorageKeys;
import com.emergency.common.storage.StorageRecord;
import com.emergency.common.storage.StorageUnavailableException;
import com.emergency.common.storage.VersionConflictException;
import com.emergency.request.domain.ConcurrentUpdateException;
import com.emergency.request.domain.DependencyUnavailableException;
import com.emergency.request.domain.RequestNotFoundException;
import com.emergency.request.domain.RequestRepository;
import com.emergency.request.domain.StoredRequest;

/**
 * Stores requests through the coordinator, in the format of docs/data-format.md §5:
 *
 * <pre>
 * key      request:{requestId}                      (shard = crc32(requestId) mod 3)
 * value    the RequestDocument
 * indexes  collection=request, type, status, geoCell (status kept in sync on every write)
 * </pre>
 *
 * It translates storage outcomes into domain exceptions, so the domain never sees HTTP.
 */
public class StorageRequestRepository implements RequestRepository {

    private final StorageClient storage;

    public StorageRequestRepository(StorageClient storage) {
        this.storage = storage;
    }

    @Override
    public StoredRequest create(RequestDocument request) {
        String key = StorageKeys.request(request.requestId());
        try {
            return toStored(storage.create(key, request, indexes(request)));
        } catch (VersionConflictException e) {
            // A fresh random UUID that already exists: practically impossible, but never overwrite.
            throw new IllegalStateException("request id collision: " + request.requestId(), e);
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        } catch (InvalidStorageRequestException e) {
            throw new IllegalStateException("storage rejected a request record (bug): " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StoredRequest> find(String requestId) {
        try {
            return storage.get(StorageKeys.request(requestId)).map(this::toStored);
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        }
    }

    @Override
    public StoredRequest update(RequestDocument request, long expectedVersion) {
        String key = StorageKeys.request(request.requestId());
        try {
            return toStored(storage.replace(key, request, indexes(request), expectedVersion));
        } catch (VersionConflictException e) {
            throw new ConcurrentUpdateException("request " + request.requestId() + " changed meanwhile", e);
        } catch (PreconditionFailedException e) {
            throw new RequestNotFoundException("no request with id " + request.requestId());
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        } catch (InvalidStorageRequestException e) {
            throw new IllegalStateException("storage rejected a request record (bug): " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String requestId, long expectedVersion) {
        try {
            storage.delete(StorageKeys.request(requestId), expectedVersion);
        } catch (RecordNotFoundException e) {
            // already gone: nothing to do
        } catch (VersionConflictException e) {
            throw new ConcurrentUpdateException("request " + requestId + " changed meanwhile", e);
        } catch (StorageUnavailableException e) {
            throw unavailable(e);
        }
    }

    /** The searchable copy of some fields; must change together with the value. */
    static Map<String, String> indexes(RequestDocument r) {
        return Map.of(
                "collection", StorageKeys.REQUEST,
                "type", r.type().name(),
                "status", r.status().name(),
                "geoCell", r.geoCell());
    }

    private StoredRequest toStored(StorageRecord record) {
        return new StoredRequest(storage.valueAs(record, RequestDocument.class), record.version());
    }

    private static DependencyUnavailableException unavailable(StorageUnavailableException e) {
        return new DependencyUnavailableException("storage unavailable: " + e.getMessage(), e);
    }
}
