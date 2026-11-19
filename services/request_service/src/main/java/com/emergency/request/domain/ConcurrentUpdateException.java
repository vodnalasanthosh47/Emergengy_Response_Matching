package com.emergency.request.domain;

/** Someone else changed the request between our read and our write (storage 409). Handled inside the domain by re-reading; never reaches the client. */
public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String message) {
        super(message);
    }

    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
