package com.emergency.matching.domain;

import java.util.Optional;

import com.emergency.common.request.RequestDocument;

/** Requests in storage, as matching needs them. Implemented on the coordinator in the storage package. */
public interface RequestStore {

    Optional<Versioned<RequestDocument>> find(String requestId);

    /**
     * Replaces the request if it is still at {@code expectedVersion}.
     *
     * @throws ConcurrentUpdateException if someone else changed it first
     */
    Versioned<RequestDocument> update(RequestDocument request, long expectedVersion);
}
