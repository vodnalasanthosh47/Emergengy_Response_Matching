package com.emergency.common.model;

/** A point in WGS84 degrees (contracts/openapi/common.yaml#/components/schemas/Location). */
public record Location(Double lat, Double lng) {

    /**
     * Whether both coordinates are present and within range. Deliberately not named
     * {@code isValid()}: Jackson treats {@code isXxx()} as a property and would store
     * {@code "valid": true} inside every location.
     */
    public boolean inRange() {
        return lat != null && lng != null && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }
}
