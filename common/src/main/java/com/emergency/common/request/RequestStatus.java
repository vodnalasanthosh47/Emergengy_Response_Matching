package com.emergency.common.request;

import java.util.EnumSet;
import java.util.Set;

/**
 * A request's lifecycle (spec §3.2):
 *
 * <pre>
 * PENDING ──► MATCHED ──► IN_PROGRESS ──► FULFILLED
 *    │  ▲         │
 *    │  └ UNMATCHED (no candidate yet; may be retried)
 *    └──► CANCELLED  (allowed from PENDING / UNMATCHED / MATCHED)
 * </pre>
 *
 * The transition table lives here, in the shared contract, because both the Request
 * Service (client status updates) and the Matching Service (MATCHED / UNMATCHED) must
 * apply exactly the same rules.
 */
public enum RequestStatus {
    PENDING, UNMATCHED, MATCHED, IN_PROGRESS, FULFILLED, CANCELLED;

    /** Whether a request in this status may move to {@code next}. */
    public boolean canTransitionTo(RequestStatus next) {
        return allowedNext().contains(next);
    }

    /** The statuses this one may move to. Empty for final statuses. */
    public Set<RequestStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(MATCHED, UNMATCHED, CANCELLED);
            case UNMATCHED -> EnumSet.of(PENDING, MATCHED, CANCELLED);
            case MATCHED -> EnumSet.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(FULFILLED);
            case FULFILLED, CANCELLED -> EnumSet.noneOf(RequestStatus.class);
        };
    }

    /** Whether matching may (still) try to find a resource for a request in this status. */
    public boolean isMatchable() {
        return this == PENDING || this == UNMATCHED;
    }
}
