package com.emergency.resource.exception;

/** Storage 409: someone changed the resource between our read and our write. The service re-reads; clients never see it. */
public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
