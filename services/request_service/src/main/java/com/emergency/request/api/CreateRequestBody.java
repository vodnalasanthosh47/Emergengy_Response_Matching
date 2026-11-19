package com.emergency.request.api;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.Requester;
import com.emergency.request.domain.NewRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /requests} (CreateRequest in contracts/openapi/request-service-api.yaml).
 * Bean Validation checks the generic fields here; the per-type {@code details} rules are in
 * the domain's DetailsValidator. Unknown fields are rejected by Jackson configuration.
 */
public record CreateRequestBody(
        @NotNull Category type,
        @NotNull @Valid LocationBody location,
        @NotNull JsonNode details,
        @Valid RequesterBody requester) {

    public record LocationBody(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    public record RequesterBody(@Size(max = 200) String name, @Size(max = 200) String contact) {
    }

    NewRequest toCommand() {
        return new NewRequest(type, new Location(location.lat(), location.lng()), details,
                requester == null ? null : new Requester(requester.name(), requester.contact()));
    }
}
