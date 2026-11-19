package com.emergency.common.geo;

import com.emergency.common.model.Location;

/** Great-circle distance, used by matching to rank candidates (spec §3.4, §5). */
public final class GeoDistance {

    /** Mean Earth radius in kilometres. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistance() {
    }

    /** Haversine distance between two points, in kilometres. */
    public static double km(Location a, Location b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
