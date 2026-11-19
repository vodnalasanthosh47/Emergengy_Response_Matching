package com.emergency.matching.domain;

import java.math.BigDecimal;

import com.emergency.common.request.RequestDocument;

import tools.jackson.databind.JsonNode;

/**
 * What a request needs, in the unit its type's resources count in
 * (docs/service-interfaces.md §3):
 *
 * <pre>
 * WATER, FOOD, MEDICINE   details.quantity   (L, MEALS, UNITS)
 * TRANSPORT, SHELTER      details.people     (PEOPLE)
 * </pre>
 *
 * @param medicineName for MEDICINE, the resource must offer this medicine; otherwise null
 */
public record Demand(BigDecimal amount, String medicineName) {

    public static Demand of(RequestDocument request) {
        JsonNode details = request.details();
        String field = switch (request.type()) {
            case WATER, FOOD, MEDICINE -> "quantity";
            case TRANSPORT, SHELTER -> "people";
        };
        JsonNode amount = details == null ? null : details.get(field);
        if (amount == null || !amount.isNumber() || amount.decimalValue().signum() <= 0) {
            // The Request Service validated it; a stored request without it is corrupt.
            throw new IllegalStateException("request " + request.requestId() + " has no valid details." + field);
        }
        String medicine = switch (request.type()) {
            case MEDICINE -> details.path("name").asString(null);
            default -> null;
        };
        return new Demand(amount.decimalValue(), medicine);
    }
}
