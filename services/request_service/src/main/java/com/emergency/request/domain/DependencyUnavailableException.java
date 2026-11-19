package com.emergency.request.domain;

/** 503 SERVICE_UNAVAILABLE: storage or RabbitMQ could not be reached; the client may retry. */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) {
        super(message);
    }

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
