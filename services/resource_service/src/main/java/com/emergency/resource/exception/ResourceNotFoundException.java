package com.emergency.resource.exception;

/** 404 NOT_FOUND. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
