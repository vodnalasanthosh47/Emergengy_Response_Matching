package com.emergency.matching.domain;

import com.emergency.common.events.RematchRequestedEvent;
import com.emergency.common.model.Category;

/** Puts a waiting request back on the matching queue (a RematchRequested message). */
public interface RematchPublisher {

    /** @throws DependencyUnavailableException if the message broker cannot be reached */
    void publish(String requestId, Category type, RematchRequestedEvent.Reason reason);
}
