package com.emergency.resource.exception;

/** 503 SERVICE_UNAVAILABLE: the storage coordinator could not be reached; the client may retry. */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
