package com.emergency.matching.domain;

/** Storage could not be reached; the caller answers 503 or retries the event. */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) {
        super(message);
    }

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
