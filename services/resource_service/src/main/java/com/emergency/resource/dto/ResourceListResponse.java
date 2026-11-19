package com.emergency.resource.dto;

import java.util.List;

import com.emergency.common.resource.ResourceView;

public record ResourceListResponse(List<ResourceView> resources) {
}
