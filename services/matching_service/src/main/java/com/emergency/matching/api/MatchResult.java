package com.emergency.matching.api;

import com.emergency.common.request.RequestView;
import com.emergency.common.resource.ResourceView;

/** Body of a successful {@code POST /match/{requestId}} (MatchResult in contracts/openapi/matching-service-api.yaml). */
public record MatchResult(String requestId, String resourceId, double distanceKm, RequestView request,
        ResourceView resource) {
}
