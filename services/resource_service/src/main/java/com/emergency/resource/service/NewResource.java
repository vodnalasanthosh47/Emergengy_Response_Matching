package com.emergency.resource.service;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.Provider;

import tools.jackson.databind.JsonNode;

/**
 * What a client sends in {@code POST /resources}, before the server adds ID, cell, status
 * and times. {@code capacity.remaining}, {@code capacity.unit} and {@code details} may be null.
 */
public record NewResource(Category type, Location location, Provider provider, Capacity capacity, JsonNode details) {
}
