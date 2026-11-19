package com.emergency.resource.dto;

import java.math.BigDecimal;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.Provider;
import com.emergency.common.resource.ProviderKind;
import com.emergency.resource.service.NewResource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /resources}. Generic field checks are here; the per-type capacity and
 * details rules are in ResourceValidator.
 */
public record RegisterResourceRequest(
        @NotNull Category type,
        @NotNull @Valid LocationBody location,
        @NotNull @Valid ProviderBody provider,
        @NotNull @Valid CapacityBody capacity,
        JsonNode details) {

    public record LocationBody(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    public record ProviderBody(
            @NotBlank @Size(max = 200) String name,
            @NotNull ProviderKind kind,
            @Size(max = 200) String contact) {
    }

    public record CapacityBody(@NotNull BigDecimal total, BigDecimal remaining, String unit) {
    }

    public NewResource toCommand() {
        return new NewResource(type, new Location(location.lat(), location.lng()),
                new Provider(provider.name(), provider.kind(), provider.contact()),
                new Capacity(capacity.total(), capacity.remaining(), capacity.unit()), details);
    }
}
