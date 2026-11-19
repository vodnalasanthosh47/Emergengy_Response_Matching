package com.emergency.matching.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emergency.common.geo.GeoHash;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.resource.ResourceView;
import com.emergency.matching.domain.CandidateRanking.Candidate;

/**
 * The matching algorithm (spec §5), used both for RequestCreated events and for
 * POST /match/{requestId}:
 *
 * <ol>
 * <li>Re-read the request; only PENDING and UNMATCHED requests are matched. This is what
 *     makes a redelivered event or a repeated call harmless: once MATCHED, nothing happens.</li>
 * <li>Fetch AVAILABLE resources of the request's type in its cell and the 8 neighbours.</li>
 * <li>Keep those that fit and rank them ({@link CandidateRanking}).</li>
 * <li>Claim the best one with a conditional write: remaining minus the amount, DEPLETED at 0.
 *     If someone else changed it first (409), re-read it: try again if it still fits,
 *     otherwise move on. At most {@value #MAX_CLAIM_ATTEMPTS} claim attempts in total.</li>
 * <li>Mark the request MATCHED with the resource's ID, again conditionally. If the request
 *     changed meanwhile and is no longer matchable (cancelled, or matched by a concurrent
 *     attempt), give the capacity back (compensating write).</li>
 * <li>No resource could be claimed: mark the request UNMATCHED.</li>
 * </ol>
 *
 * No double booking: a resource's capacity only ever changes through a conditional write
 * on the version just read, so two claims on the same version cannot both succeed.
 */
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    static final int MAX_CLAIM_ATTEMPTS = 5;
    static final int MAX_REQUEST_ATTEMPTS = 3;
    static final int MAX_RELEASE_ATTEMPTS = 5;

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RequestStore requests;
    private final ResourceStore resources;
    private final CandidateRanking ranking;
    private final Clock clock;
    private final int candidateLimit;

    public MatchingService(RequestStore requests, ResourceStore resources, CandidateRanking ranking, Clock clock,
            int candidateLimit) {
        this.requests = requests;
        this.resources = resources;
        this.ranking = ranking;
        this.clock = clock;
        this.candidateLimit = candidateLimit;
    }

    /** @throws InvalidRequestException if {@code requestId} is not a UUID */
    public MatchOutcome match(String requestId) {
        String id = parseId(requestId);
        Optional<Versioned<RequestDocument>> found = requests.find(id);
        if (found.isEmpty()) {
            return new MatchOutcome.NotFound(id);
        }
        Versioned<RequestDocument> request = found.get();
        if (!request.document().status().isMatchable()) {
            log.info("request {} is {}: nothing to match", id, request.document().status());
            return new MatchOutcome.NotMatchable(view(request));
        }

        RequestDocument doc = request.document();
        Demand demand = Demand.of(doc);
        List<String> cells = GeoHash.withNeighbours(doc.geoCell());
        List<Candidate> candidates = ranking.rank(doc, demand, resources.findAvailable(doc.type(), cells, candidateLimit));

        Optional<Claim> claim = claimFirst(candidates, doc, demand);
        if (claim.isEmpty()) {
            return markUnmatched(request);
        }
        return assign(request, claim.get(), demand);
    }

    /** A successful claim: the resource after our write, and its distance. */
    private record Claim(Versioned<ResourceDocument> resource, double distanceKm) {
    }

    private Optional<Claim> claimFirst(List<Candidate> candidates, RequestDocument request, Demand demand) {
        int attempts = 0;
        for (Candidate candidate : candidates) {
            Versioned<ResourceDocument> current = candidate.resource();
            while (attempts < MAX_CLAIM_ATTEMPTS && ranking.fits(current.document(), request.type(), demand)) {
                attempts++;
                try {
                    Versioned<ResourceDocument> claimed = resources.update(claimed(current.document(), demand), current.version());
                    log.info("claimed {} {} of {} for request {}", demand.amount(), claimed.document().capacity().unit(),
                            claimed.document().resourceId(), request.requestId());
                    return Optional.of(new Claim(claimed, candidate.distanceKm()));
                } catch (ConcurrentUpdateException e) {
                    // Someone claimed or changed it first. Look again: it may still fit.
                    Optional<Versioned<ResourceDocument>> fresh = resources.find(current.document().resourceId());
                    if (fresh.isEmpty()) {
                        break;
                    }
                    current = fresh.get();
                }
            }
            if (attempts >= MAX_CLAIM_ATTEMPTS) {
                log.warn("request {}: gave up after {} claim attempts", request.requestId(), attempts);
                break;
            }
        }
        return Optional.empty();
    }

    /** Marks the request MATCHED to the claimed resource; gives the capacity back if it can't. */
    private MatchOutcome assign(Versioned<RequestDocument> request, Claim claim, Demand demand) {
        String resourceId = claim.resource().document().resourceId();
        String requestId = request.document().requestId();
        Versioned<RequestDocument> current = request;
        try {
            for (int attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt++) {
                try {
                    Versioned<RequestDocument> matched = requests.update(current.document().matchedTo(resourceId, now()),
                            current.version());
                    log.info("request {} MATCHED to {} ({} km)", matched.document().requestId(), resourceId,
                            String.format(Locale.ROOT, "%.2f", claim.distanceKm()));
                    return new MatchOutcome.Matched(view(matched), resourceView(claim.resource()), claim.distanceKm());
                } catch (ConcurrentUpdateException e) {
                    Optional<Versioned<RequestDocument>> fresh = requests.find(requestId);
                    if (fresh.isEmpty() || !fresh.get().document().status().isMatchable()) {
                        // Cancelled, or matched by a concurrent attempt: undo our claim.
                        release(claim, demand);
                        return fresh.<MatchOutcome>map(r -> new MatchOutcome.NotMatchable(view(r)))
                                .orElseGet(() -> new MatchOutcome.NotFound(requestId));
                    }
                    current = fresh.get(); // changed but still matchable: try again on the new version
                }
            }
        } catch (RuntimeException e) {
            // Storage failed while updating the request: try to give the capacity back so it
            // is not lost (if storage is down this fails too; that gap is documented, spec §5).
            release(claim, demand);
            throw e;
        }
        release(claim, demand);
        throw new ConcurrentUpdateException("request " + requestId + " is being changed concurrently");
    }

    /** Compensating write: adds the claimed amount back to the resource. Best effort. */
    private void release(Claim claim, Demand demand) {
        Versioned<ResourceDocument> current = claim.resource();
        String id = current.document().resourceId();
        for (int attempt = 1; attempt <= MAX_RELEASE_ATTEMPTS; attempt++) {
            ResourceDocument r = current.document();
            BigDecimal remaining = r.capacity().remaining().add(demand.amount()).min(r.capacity().total());
            // DEPLETED → AVAILABLE again; OFFLINE stays OFFLINE (the provider withdrew it).
            ResourceStatus status = r.status() == ResourceStatus.DEPLETED ? ResourceStatus.AVAILABLE : r.status();
            try {
                resources.update(r.withCapacity(new Capacity(r.capacity().total(), remaining, r.capacity().unit()), status, now()),
                        current.version());
                log.info("released {} back to {}", demand.amount(), id);
                return;
            } catch (ConcurrentUpdateException e) {
                Optional<Versioned<ResourceDocument>> fresh = resources.find(id);
                if (fresh.isEmpty()) {
                    return;
                }
                current = fresh.get();
            } catch (RuntimeException e) {
                log.error("could not give {} back to {}: capacity is lost until fixed by hand", demand.amount(), id, e);
                return;
            }
        }
        log.error("could not give {} back to {} after {} attempts", demand.amount(), id, MAX_RELEASE_ATTEMPTS);
    }

    private MatchOutcome markUnmatched(Versioned<RequestDocument> request) {
        Versioned<RequestDocument> current = request;
        for (int attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt++) {
            if (current.document().status() == RequestStatus.UNMATCHED) {
                return new MatchOutcome.NoMatch(view(current)); // already says so: no write
            }
            try {
                Versioned<RequestDocument> updated = requests.update(
                        current.document().withStatus(RequestStatus.UNMATCHED, now()), current.version());
                log.info("request {} UNMATCHED: no available resource nearby", updated.document().requestId());
                return new MatchOutcome.NoMatch(view(updated));
            } catch (ConcurrentUpdateException e) {
                Optional<Versioned<RequestDocument>> fresh = requests.find(current.document().requestId());
                if (fresh.isEmpty()) {
                    return new MatchOutcome.NotFound(current.document().requestId());
                }
                if (!fresh.get().document().status().isMatchable()) {
                    return new MatchOutcome.NotMatchable(view(fresh.get())); // e.g. matched concurrently
                }
                current = fresh.get();
            }
        }
        throw new ConcurrentUpdateException("request " + request.document().requestId() + " is being changed concurrently");
    }

    /** The claimed copy: remaining minus the amount; DEPLETED when nothing is left. */
    private ResourceDocument claimed(ResourceDocument r, Demand demand) {
        BigDecimal remaining = r.capacity().remaining().subtract(demand.amount());
        ResourceStatus status = remaining.signum() == 0 ? ResourceStatus.DEPLETED : ResourceStatus.AVAILABLE;
        return r.withCapacity(new Capacity(r.capacity().total(), remaining, r.capacity().unit()), status, now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static RequestView view(Versioned<RequestDocument> r) {
        return RequestView.of(r.document(), r.version());
    }

    private static ResourceView resourceView(Versioned<ResourceDocument> r) {
        return ResourceView.of(r.document(), r.version());
    }

    private static String parseId(String requestId) {
        if (requestId == null || !UUID_TEXT.matcher(requestId).matches()) {
            throw new InvalidRequestException("requestId must be a UUID");
        }
        return requestId.toLowerCase(Locale.ROOT);
    }
}
