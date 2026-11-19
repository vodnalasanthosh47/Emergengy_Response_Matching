package com.emergency.matching.domain;

import com.emergency.common.request.RequestView;
import com.emergency.common.resource.ResourceView;

/**
 * What one matching attempt ended with. The REST API turns these into 200 / 422 / 409 /
 * 404; the event consumer acknowledges all of them (each is a final answer for this
 * attempt).
 */
public sealed interface MatchOutcome {

    /** A resource was claimed and the request is now MATCHED to it. */
    record Matched(RequestView request, ResourceView resource, double distanceKm) implements MatchOutcome {
    }

    /** Nothing nearby can serve the request; it is now UNMATCHED. */
    record NoMatch(RequestView request) implements MatchOutcome {
    }

    /** The request is not PENDING or UNMATCHED (already matched, cancelled, ...): nothing was done. */
    record NotMatchable(RequestView request) implements MatchOutcome {
    }

    /** There is no such request. */
    record NotFound(String requestId) implements MatchOutcome {
    }
}
