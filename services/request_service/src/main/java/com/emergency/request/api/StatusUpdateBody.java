package com.emergency.request.api;

import com.emergency.common.request.RequestStatus;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /requests/{id}/status}. */
public record StatusUpdateBody(@NotNull RequestStatus status) {
}
