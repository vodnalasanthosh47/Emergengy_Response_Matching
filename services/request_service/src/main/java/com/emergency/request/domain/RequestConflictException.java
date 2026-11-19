package com.emergency.request.domain;

/** 409 CONFLICT: the request's current status does not allow the change. */
public class RequestConflictException extends RuntimeException {
    public RequestConflictException(String message) {
        super(message);
    }

    public RequestConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
