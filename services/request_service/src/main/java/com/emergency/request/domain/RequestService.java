package com.emergency.request.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emergency.common.geo.GeoHash;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.common.request.Requester;

/**
 * The Request Service's business logic: create a request, read it, change its status.
 * It knows nothing about HTTP or RabbitMQ; it talks to a {@link RequestRepository} and a
 * {@link RequestEventPublisher}, so it can be tested with simple fakes.
 */
public class RequestService {

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    /**
     * Statuses a client may set with PUT /requests/{id}/status. MATCHED and UNMATCHED are
     * set only by the Matching Service, because MATCHED must record which resource was
     * claimed (assignedResourceId) in the same write that claims it.
     */
    static final Set<RequestStatus> CLIENT_SETTABLE =
            EnumSet.of(RequestStatus.PENDING, RequestStatus.IN_PROGRESS, RequestStatus.FULFILLED, RequestStatus.CANCELLED);

    /** How often a status change is retried when another writer changed the request first. */
    static final int MAX_UPDATE_ATTEMPTS = 3;

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RequestRepository repository;
    private final RequestEventPublisher events;
    private final DetailsValidator detailsValidator;
    private final Clock clock;
    private final int geohashPrecision;

    public RequestService(RequestRepository repository, RequestEventPublisher events, DetailsValidator detailsValidator,
            Clock clock, int geohashPrecision) {
        this.repository = repository;
        this.events = events;
        this.detailsValidator = detailsValidator;
        this.clock = clock;
        this.geohashPrecision = geohashPrecision;
    }

    /**
     * Validates and stores a new request, then announces it on RabbitMQ.
     *
     * <p>If the announcement fails, the stored request is deleted again before answering
     * 503: otherwise it would stay PENDING forever with no event for the Matching Service,
     * and the client's retry would create a second copy. (If the service crashes between
     * the two steps the request is still orphaned; that gap is documented as future work,
     * spec §5.)
     */
    public RequestView create(NewRequest command) {
        if (command.type() == null) {
            throw new InvalidRequestException("type is required");
        }
        if (command.location() == null || !command.location().inRange()) {
            throw new InvalidRequestException("location must have lat in [-90, 90] and lng in [-180, 180]");
        }
        Instant now = now();
        RequestDocument document = new RequestDocument(
                UUID.randomUUID().toString(),
                command.type(),
                command.location(),
                GeoHash.encode(command.location().lat(), command.location().lng(), geohashPrecision),
                RequestStatus.PENDING,
                clean(command.requester()),
                detailsValidator.validate(command.type(), command.details()),
                null,
                now,
                now);

        StoredRequest stored = repository.create(document);
        try {
            events.publishCreated(stored.view());
        } catch (DependencyUnavailableException e) {
            undoCreate(stored);
            throw e;
        }
        log.info("request created: id={} type={} cell={}", document.requestId(), document.type(), document.geoCell());
        return stored.view();
    }

    public RequestView get(String requestId) {
        String id = parseId(requestId);
        return repository.find(id).map(StoredRequest::view)
                .orElseThrow(() -> new RequestNotFoundException("no request with id " + id));
    }

    /**
     * Moves a request to {@code target} if the status machine allows it. Setting the
     * status it already has is a no-op, so a client may safely retry after a 503.
     *
     * <p>Read, check, then write with expectedVersion: if another writer (a client, or the
     * Matching Service setting MATCHED) got in between, storage answers 409, and the
     * decision is made again on the fresh state.
     */
    public RequestView updateStatus(String requestId, RequestStatus target) {
        String id = parseId(requestId);
        if (target == null) {
            throw new InvalidRequestException("status is required");
        }
        if (!CLIENT_SETTABLE.contains(target)) {
            throw new InvalidRequestException("status " + target + " is set by the Matching Service, not by clients");
        }
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            StoredRequest current = repository.find(id)
                    .orElseThrow(() -> new RequestNotFoundException("no request with id " + id));
            RequestStatus from = current.document().status();
            if (from == target) {
                return current.view();
            }
            if (!from.canTransitionTo(target)) {
                throw new RequestConflictException("cannot change status from " + from + " to " + target);
            }
            try {
                StoredRequest updated = repository.update(current.document().withStatus(target, now()), current.version());
                log.info("request status changed: id={} {} -> {}", id, from, target);
                return updated.view();
            } catch (ConcurrentUpdateException e) {
                log.debug("request {} changed concurrently (attempt {}); re-reading", id, attempt);
            }
        }
        throw new RequestConflictException("request " + id + " is being changed concurrently; retry");
    }

    private void undoCreate(StoredRequest stored) {
        String id = stored.document().requestId();
        try {
            repository.delete(id, stored.version());
            log.warn("request {} removed again: its RequestCreated event could not be published", id);
        } catch (RuntimeException cleanup) {
            log.error("request {} stored but neither published nor removed; it stays PENDING without an event", id, cleanup);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Request IDs are UUIDs; anything else cannot be a request (and must not reach the storage key). */
    private static String parseId(String requestId) {
        if (requestId == null || !UUID_TEXT.matcher(requestId).matches()) {
            throw new InvalidRequestException("requestId must be a UUID");
        }
        return requestId.toLowerCase(Locale.ROOT);
    }

    private static Requester clean(Requester requester) {
        if (requester == null) {
            return null;
        }
        return new Requester(strip(requester.name()), strip(requester.contact()));
    }

    private static String strip(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
