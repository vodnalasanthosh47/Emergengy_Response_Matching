package com.emergency.request.domain;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.Requester;

import tools.jackson.databind.JsonNode;

/** What a client asks for in {@code POST /requests}, before the server adds ID, cell, status and times. */
public record NewRequest(Category type, Location location, JsonNode details, Requester requester) {
}
