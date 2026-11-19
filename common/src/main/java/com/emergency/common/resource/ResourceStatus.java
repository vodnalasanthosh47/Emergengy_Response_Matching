package com.emergency.common.resource;

import java.util.EnumSet;
import java.util.Set;

/**
 * A resource's lifecycle (spec §3.3). The table lives in common because the Resource
 * Service (client updates) and the Matching Service (claims and releases) must apply the
 * same rules.
 *
 * <pre>
 * AVAILABLE ──► DEPLETED   (a claim used up the remaining capacity)
 * AVAILABLE ──► OFFLINE    (withdrawn by the provider)
 * DEPLETED  ──► AVAILABLE  (capacity released again)
 * DEPLETED  ──► OFFLINE
 * OFFLINE   ──► AVAILABLE
 * </pre>
 */
public enum ResourceStatus {
    AVAILABLE, DEPLETED, OFFLINE;

    public boolean canTransitionTo(ResourceStatus next) {
        return allowedNext().contains(next);
    }

    public Set<ResourceStatus> allowedNext() {
        return switch (this) {
            case AVAILABLE -> EnumSet.of(DEPLETED, OFFLINE);
            case DEPLETED -> EnumSet.of(AVAILABLE, OFFLINE);
            case OFFLINE -> EnumSet.of(AVAILABLE);
        };
    }
}
