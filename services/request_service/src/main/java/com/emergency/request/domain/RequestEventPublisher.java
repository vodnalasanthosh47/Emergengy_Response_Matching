package com.emergency.request.domain;

import com.emergency.common.request.RequestView;

/** Announces new requests to the Matching Service. Implemented on RabbitMQ in the messaging package. */
public interface RequestEventPublisher {

    /**
     * Publishes {@code RequestCreated} and returns only once the broker has confirmed it.
     *
     * @throws DependencyUnavailableException if the broker is unreachable or did not confirm
     */
    void publishCreated(RequestView request);
}
