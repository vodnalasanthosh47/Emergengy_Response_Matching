package com.emergency.request.domain;

import java.util.Optional;

import com.emergency.common.request.RequestDocument;

/**
 * Where requests are kept. The domain depends only on this interface; the storage
 * package implements it on top of the coordinator (key {@code request:{requestId}}).
 */
public interface RequestRepository {

    /** Stores a new request (it must not exist yet). */
    StoredRequest create(RequestDocument request);

    Optional<StoredRequest> find(String requestId);

    /**
     * Replaces the request if it is still at {@code expectedVersion}.
     *
     * @throws ConcurrentUpdateException if it changed meanwhile
     * @throws RequestNotFoundException  if it no longer exists
     */
    StoredRequest update(RequestDocument request, long expectedVersion);

    /** Removes the request if it is still at {@code expectedVersion}. */
    void delete(String requestId, long expectedVersion);
}
