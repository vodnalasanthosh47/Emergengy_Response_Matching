package com.emergency.matching.api;

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
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.emergency.common.api.ErrorResponse;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.InvalidRequestException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every failure into the one error JSON of spec §4.1:
 * {@code {"error": "...", "message": "...", "timestamp": "..."}}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_REQUEST, e.getMessage());
    }

    /** The request kept changing under us (rare): the caller may simply try again. */
    @ExceptionHandler(ConcurrentUpdateException.class)
    ResponseEntity<ErrorResponse> conflict(ConcurrentUpdateException e) {
        return error(HttpStatus.CONFLICT, ErrorResponse.CONFLICT, e.getMessage() + "; retry");
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(DependencyUnavailableException e) {
        log.warn("dependency unavailable: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.SERVICE_UNAVAILABLE,
                "a dependency is unavailable, retry later");
    }

    /** Bean Validation on the body: report every bad field, e.g. "location.lat must be ...". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_REQUEST, message);
    }

    /** Body is not JSON, has an unknown field, or a value of the wrong type (e.g. type "water"). */
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

    /** "location.lat", "details" ... from Jackson's path to the failing value. */
    private static String path(JacksonException e) {
        return e.getPath().stream()
                .map(r -> r.getPropertyName() != null ? r.getPropertyName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
