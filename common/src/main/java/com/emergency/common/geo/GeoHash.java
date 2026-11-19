package com.emergency.common.geo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Geohash encoding: turns a latitude/longitude into a short string naming the grid cell
 * that contains it. Nearby points share a prefix, and a cell at precision 6 is about
 * 0.6 km × 1.2 km near Bengaluru.
 *
 * <p>How it works: the world's longitude range [-180, 180] and latitude range [-90, 90]
 * are halved again and again, alternating longitude, latitude, longitude, ... Each halving
 * gives one bit (1 = the upper half). Every 5 bits become one base-32 character.
 *
 * <p>Every service must compute cells with this class (spec §3.4), because resources are
 * stored on the shard of their cell: a cell computed differently means a resource that is
 * never found. Written here instead of using a library so it can be explained line by line.
 */
public final class GeoHash {

    /** The geohash base-32 alphabet: digits and letters without a, i, l, o. */
    public static final String ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";
    public static final int MAX_PRECISION = 12;

    private static final Pattern VALID = Pattern.compile("^[0123456789bcdefghjkmnpqrstuvwxyz]{1,12}$");

    private GeoHash() {
    }

    /** Returns the cell of the given point at the given precision (1–12 characters). */
    public static String encode(double lat, double lng, int precision) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be 1.." + MAX_PRECISION + ", got " + precision);
        }
        if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) { // also rejects NaN
            throw new IllegalArgumentException("invalid coordinates: " + lat + ", " + lng);
        }
        double[] latRange = {-90, 90};
        double[] lngRange = {-180, 180};
        StringBuilder cell = new StringBuilder(precision);
        boolean lngBit = true; // bits alternate, starting with longitude
        int bits = 0;
        int value = 0;
        while (cell.length() < precision) {
            double[] range = lngBit ? lngRange : latRange;
            double coordinate = lngBit ? lng : lat;
            double mid = (range[0] + range[1]) / 2;
            value <<= 1;
            if (coordinate >= mid) {
                value |= 1;
                range[0] = mid;
            } else {
                range[1] = mid;
            }
            lngBit = !lngBit;
            if (++bits == 5) {
                cell.append(ALPHABET.charAt(value));
                bits = 0;
                value = 0;
            }
        }
        return cell.toString();
    }

    /** Returns the rectangle a cell covers. */
    public static Box decode(String cell) {
        requireValid(cell);
        double[] latRange = {-90, 90};
        double[] lngRange = {-180, 180};
        boolean lngBit = true;
        for (char c : cell.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            for (int shift = 4; shift >= 0; shift--) {
                double[] range = lngBit ? lngRange : latRange;
                double mid = (range[0] + range[1]) / 2;
                if (((value >> shift) & 1) == 1) {
                    range[0] = mid;
                } else {
                    range[1] = mid;
                }
                lngBit = !lngBit;
            }
        }
        return new Box(latRange[0], latRange[1], lngRange[0], lngRange[1]);
    }

    /**
     * Returns the cell and its 8 neighbours (north-west first, row by row, south-east last):
     * the cells a nearby-resource search looks in (spec §3.4). Longitude wraps around the
     * antimeridian; near a pole there are fewer than 9 distinct cells.
     */
    public static List<String> withNeighbours(String cell) {
        Box box = decode(cell);
        double height = box.maxLat() - box.minLat();
        double width = box.maxLng() - box.minLng();
        Set<String> cells = new LinkedHashSet<>();
        for (int dy = 1; dy >= -1; dy--) {
            double lat = box.centerLat() + dy * height;
            if (lat > 90 || lat < -90) {
                continue; // beyond a pole: no neighbour there
            }
            for (int dx = -1; dx <= 1; dx++) {
                double lng = box.centerLng() + dx * width;
                if (lng > 180) {
                    lng -= 360;
                } else if (lng < -180) {
                    lng += 360;
                }
                cells.add(encode(lat, lng, cell.length()));
            }
        }
        return new ArrayList<>(cells);
    }

    /** Whether {@code cell} is a lower-case geohash of 1–12 characters. */
    public static boolean isValid(String cell) {
        return cell != null && VALID.matcher(cell).matches();
    }

    private static void requireValid(String cell) {
        if (!isValid(cell)) {
            throw new IllegalArgumentException("not a geohash: " + cell);
        }
    }

    /** The rectangle covered by a cell, in degrees. */
    public record Box(double minLat, double maxLat, double minLng, double maxLng) {
        public double centerLat() {
            return (minLat + maxLat) / 2;
        }

        public double centerLng() {
            return (minLng + maxLng) / 2;
        }

        public boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }
    }
}
