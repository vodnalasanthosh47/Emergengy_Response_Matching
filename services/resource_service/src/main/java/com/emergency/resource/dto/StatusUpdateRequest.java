package com.emergency.resource.dto;

import com.emergency.common.resource.ResourceStatus;

import jakarta.validation.constraints.NotNull;

public record StatusUpdateRequest(@NotNull ResourceStatus status) {
}
