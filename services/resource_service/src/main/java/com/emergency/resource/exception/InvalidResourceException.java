package com.emergency.resource.exception;

/** 400 INVALID_REQUEST. */
public class InvalidResourceException extends RuntimeException {
    public InvalidResourceException(String message) {
        super(message);
    }
}
