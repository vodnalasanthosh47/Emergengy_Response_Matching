package com.emergency.resource.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.emergency.common.geo.GeoHash;
import com.emergency.common.model.Category;
import com.emergency.common.resource.Provider;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.resource.ResourceView;
import com.emergency.common.storage.StorageKeys;
import com.emergency.resource.config.ResourceServiceProperties;
import com.emergency.resource.exception.ConcurrentUpdateException;
import com.emergency.resource.exception.InvalidResourceException;
import com.emergency.resource.exception.ResourceConflictException;
import com.emergency.resource.exception.ResourceNotFoundException;
import com.emergency.resource.repository.ResourceRepository;
import com.emergency.resource.repository.StoredResource;

@Service
public class ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);

    /**
     * DEPLETED is set only by the Matching Service, in the same write that uses up the
     * capacity, so a client can never mark a resource empty while it still has capacity.
     */
    static final Set<ResourceStatus> CLIENT_SETTABLE = EnumSet.of(ResourceStatus.AVAILABLE, ResourceStatus.OFFLINE);

    static final int MAX_UPDATE_ATTEMPTS = 3;
    static final int MAX_LIMIT = 1000;

    private final ResourceRepository repository;
    private final ResourceValidator validator;
    private final Clock clock;
    private final int geohashPrecision;

    public ResourceService(ResourceRepository repository, ResourceValidator validator, Clock clock,
            ResourceServiceProperties props) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
        this.geohashPrecision = props.geohashPrecision();
    }

    public ResourceView register(NewResource command) {
        if (command.type() == null) {
            throw new InvalidResourceException("type is required");
        }
        if (command.location() == null || !command.location().inRange()) {
            throw new InvalidResourceException("location must have lat in [-90, 90] and lng in [-180, 180]");
        }
        if (command.provider() == null || blank(command.provider().name()) || command.provider().kind() == null) {
            throw new InvalidResourceException("provider.name and provider.kind are required");
        }
        String cell = GeoHash.encode(command.location().lat(), command.location().lng(), geohashPrecision);
        Instant now = now();
        ResourceDocument document = new ResourceDocument(
                StorageKeys.resourceId(cell, UUID.randomUUID().toString()),
                command.type(),
                clean(command.provider()),
                command.location(),
                cell,
                validator.capacity(command.type(), command.capacity()),
                validator.details(command.type(), command.details()),
                ResourceStatus.AVAILABLE,
                now,
                now);

        StoredResource stored = repository.create(document);
        log.info("resource registered: id={} type={} cell={}", document.resourceId(), document.type(), cell);
        return stored.view();
    }

    public ResourceView get(String resourceId) {
        String id = parseId(resourceId);
        return repository.find(id).map(StoredResource::view)
                .orElseThrow(() -> new ResourceNotFoundException("no resource with id " + id));
    }

    public List<ResourceView> list(Category type, ResourceStatus status, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidResourceException("limit must be between 1 and " + MAX_LIMIT);
        }
        return repository.findAll(type, status, limit).stream().map(StoredResource::view).toList();
    }

    /**
     * Read, check, then write with expectedVersion; if the Matching Service claimed the
     * resource in between, storage answers 409 and the decision is made again. Setting the
     * status it already has is a no-op, so a retry after a 503 is safe.
     */
    public ResourceView updateStatus(String resourceId, ResourceStatus target) {
        String id = parseId(resourceId);
        if (target == null) {
            throw new InvalidResourceException("status is required");
        }
        if (!CLIENT_SETTABLE.contains(target)) {
            throw new InvalidResourceException("status " + target + " is set by the Matching Service, not by clients");
        }
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            StoredResource current = repository.find(id)
                    .orElseThrow(() -> new ResourceNotFoundException("no resource with id " + id));
            ResourceStatus from = current.document().status();
            if (from == target) {
                return current.view();
            }
            if (!from.canTransitionTo(target)) {
                throw new ResourceConflictException("cannot change status from " + from + " to " + target);
            }
            if (target == ResourceStatus.AVAILABLE && current.document().capacity().remaining().signum() <= 0) {
                throw new ResourceConflictException("resource has no remaining capacity");
            }
            try {
                StoredResource updated = repository.update(current.document().withStatus(target, now()), current.version());
                log.info("resource status changed: id={} {} -> {}", id, from, target);
                return updated.view();
            } catch (ConcurrentUpdateException e) {
                log.debug("resource {} changed concurrently (attempt {}); re-reading", id, attempt);
            }
        }
        throw new ResourceConflictException("resource " + id + " is being changed concurrently; retry");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Checks the {@code res_{geoCell}_{uuid}} shape before it can reach a storage key. */
    private static String parseId(String resourceId) {
        try {
            StorageKeys.resourceIdToKey(resourceId);
            return resourceId;
        } catch (IllegalArgumentException e) {
            throw new InvalidResourceException("resourceId must look like res_{geoCell}_{uuid}");
        }
    }

    private static Provider clean(Provider p) {
        return new Provider(p.name().strip(), p.kind(), blank(p.contact()) ? null : p.contact().strip());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
