package com.emergency.request.domain;

/** 400 INVALID_REQUEST: the client sent something invalid. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
