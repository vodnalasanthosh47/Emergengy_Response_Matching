package com.emergency.matching.domain;

import java.util.List;

import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;

/** Finds requests by status, for the rematch sweeper. */
public interface WaitingRequests {

    /**
     * Up to {@code limit} requests with this status, in no particular order.
     *
     * @throws DependencyUnavailableException if storage cannot be reached
     */
    List<RequestDocument> withStatus(RequestStatus status, int limit);
}
