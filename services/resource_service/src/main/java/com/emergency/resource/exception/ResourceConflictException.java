package com.emergency.resource.exception;

/** 409 CONFLICT: the resource's current state does not allow the change. */
public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
