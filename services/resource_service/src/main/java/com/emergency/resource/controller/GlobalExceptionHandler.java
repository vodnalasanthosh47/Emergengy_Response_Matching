package com.emergency.resource.controller;

import java.time.Clock;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.emergency.common.api.ErrorResponse;
import com.emergency.resource.exception.DependencyUnavailableException;
import com.emergency.resource.exception.InvalidResourceException;
import com.emergency.resource.exception.ResourceConflictException;
import com.emergency.resource.exception.ResourceNotFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** Turns every failure into the one error JSON: {@code {"error", "message", "timestamp"}}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InvalidResourceException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidResourceException e) {
        return error(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, ErrorResponse.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ResourceConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ResourceConflictException e) {
        return error(HttpStatus.CONFLICT, ErrorResponse.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(DependencyUnavailableException e) {
        log.warn("dependency unavailable: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.SERVICE_UNAVAILABLE,
                "a dependency is unavailable, retry later");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_REQUEST, message);
    }

    /** Not JSON, an unknown field, or a value of the wrong type (e.g. type "water"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String message;
        if (cause instanceof UnrecognizedPropertyException u) {
            message = "unknown field '" + u.getPropertyName() + "'";
        } else if (cause instanceof JacksonException j && !j.getPath().isEmpty()) {
            message = "invalid value for '" + path(j) + "'";
        } else {
            message = "request body must be a JSON object";
        }
        return error(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_REQUEST, message);
    }

    /** A query parameter of the wrong type, e.g. {@code ?status=busy} or {@code ?limit=abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> badParameter(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_REQUEST, "invalid value for '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorResponse.INVALID_REQUEST, "Content-Type must be application/json");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, ErrorResponse.INVALID_REQUEST, e.getMethod() + " is not supported here");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noRoute(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, ErrorResponse.NOT_FOUND, "no such endpoint");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.INTERNAL_ERROR, "internal error");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, clock));
    }

    private static String path(JacksonException e) {
        return e.getPath().stream()
                .map(r -> r.getPropertyName() != null ? r.getPropertyName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
