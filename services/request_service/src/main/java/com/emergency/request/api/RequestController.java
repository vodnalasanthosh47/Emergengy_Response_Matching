package com.emergency.request.api;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emergency.common.request.RequestView;
import com.emergency.request.domain.RequestService;

import jakarta.validation.Valid;

/**
 * The public /requests API (contracts/openapi/request-service-api.yaml). Only translates
 * HTTP to domain calls; errors become the standard JSON in {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/requests")
public class RequestController {

    private final RequestService requests;

    public RequestController(RequestService requests) {
        this.requests = requests;
    }

    @PostMapping
    public ResponseEntity<RequestView> create(@Valid @RequestBody CreateRequestBody body) {
        RequestView created = requests.create(body.toCommand());
        return ResponseEntity.created(URI.create("/requests/" + created.requestId())).body(created);
    }

    @GetMapping("/{requestId}")
    public RequestView get(@PathVariable String requestId) {
        return requests.get(requestId);
    }

    @PutMapping("/{requestId}/status")
    public RequestView updateStatus(@PathVariable String requestId, @Valid @RequestBody StatusUpdateBody body) {
        return requests.updateStatus(requestId, body.status());
    }
}
