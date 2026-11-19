package com.emergency.matching.domain;

/** The caller sent something invalid (e.g. a request ID that is not a UUID): 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
