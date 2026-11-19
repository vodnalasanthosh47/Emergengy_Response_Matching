package com.emergency.common.request;

import static com.emergency.common.request.RequestStatus.CANCELLED;
import static com.emergency.common.request.RequestStatus.FULFILLED;
import static com.emergency.common.request.RequestStatus.IN_PROGRESS;
import static com.emergency.common.request.RequestStatus.MATCHED;
import static com.emergency.common.request.RequestStatus.PENDING;
import static com.emergency.common.request.RequestStatus.UNMATCHED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RequestStatusTest {

    /** The whole table of spec §3.2, checked for all 36 pairs. */
    @Test
    void transitionTableMatchesTheSpec() {
        Map<RequestStatus, Set<RequestStatus>> allowed = Map.of(
                PENDING, EnumSet.of(MATCHED, UNMATCHED, CANCELLED),
                UNMATCHED, EnumSet.of(PENDING, MATCHED, CANCELLED),
                MATCHED, EnumSet.of(IN_PROGRESS, CANCELLED),
                IN_PROGRESS, EnumSet.of(FULFILLED),
                FULFILLED, EnumSet.noneOf(RequestStatus.class),
                CANCELLED, EnumSet.noneOf(RequestStatus.class));
        for (RequestStatus from : RequestStatus.values()) {
            for (RequestStatus to : RequestStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed.get(from).contains(to));
            }
        }
    }

    @Test
    void onlyPendingAndUnmatchedAreMatchable() {
        assertThat(EnumSet.allOf(RequestStatus.class).stream().filter(RequestStatus::isMatchable))
                .containsExactlyInAnyOrder(PENDING, UNMATCHED);
    }
}
