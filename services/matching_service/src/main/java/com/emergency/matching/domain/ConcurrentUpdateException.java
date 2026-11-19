package com.emergency.matching.domain;

/** Someone else changed the record between our read and our conditional write (storage 409). */
public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String message) {
        super(message);
    }

    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
