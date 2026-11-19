package com.emergency.common.resource;

import static com.emergency.common.resource.ResourceStatus.AVAILABLE;
import static com.emergency.common.resource.ResourceStatus.DEPLETED;
import static com.emergency.common.resource.ResourceStatus.OFFLINE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ResourceStatusTest {

    @Test
    void transitionTableMatchesTheContract() {
        Map<ResourceStatus, Set<ResourceStatus>> allowed = Map.of(
                AVAILABLE, EnumSet.of(DEPLETED, OFFLINE),
                DEPLETED, EnumSet.of(AVAILABLE, OFFLINE),
                OFFLINE, EnumSet.of(AVAILABLE));
        for (ResourceStatus from : ResourceStatus.values()) {
            for (ResourceStatus to : ResourceStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed.get(from).contains(to));
            }
        }
    }
}
