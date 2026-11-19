package com.emergency.resource.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emergency.common.model.Category;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.resource.ResourceView;
import com.emergency.resource.dto.RegisterResourceRequest;
import com.emergency.resource.dto.ResourceListResponse;
import com.emergency.resource.dto.StatusUpdateRequest;
import com.emergency.resource.service.ResourceService;

import jakarta.validation.Valid;

/** The public /resources API (contracts/openapi/resource-service-api.yaml). */
@RestController
@RequestMapping("/resources")
public class ResourceController {

    private final ResourceService resources;

    public ResourceController(ResourceService resources) {
        this.resources = resources;
    }

    @PostMapping
    public ResponseEntity<ResourceView> register(@Valid @RequestBody RegisterResourceRequest body) {
        ResourceView created = resources.register(body.toCommand());
        return ResponseEntity.created(URI.create("/resources/" + created.resourceId())).body(created);
    }

    @GetMapping
    public ResourceListResponse list(
            @RequestParam(required = false) Category type,
            @RequestParam(required = false) ResourceStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return new ResourceListResponse(resources.list(type, status, limit));
    }

    @GetMapping("/{resourceId}")
    public ResourceView get(@PathVariable String resourceId) {
        return resources.get(resourceId);
    }

    @PutMapping("/{resourceId}/status")
    public ResourceView updateStatus(@PathVariable String resourceId, @Valid @RequestBody StatusUpdateRequest body) {
        return resources.updateStatus(resourceId, body.status());
    }
}
