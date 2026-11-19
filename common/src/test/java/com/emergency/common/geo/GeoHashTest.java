package com.emergency.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeoHashTest {

    /** Reference values: the first is the standard published example; the rest were computed independently. */
    @ParameterizedTest
    @CsvSource({
            "57.64911, 10.40744, 11, u4pruydqqvj",
            "12.9716,  77.5946,   6, tdr1v9",
            "12.97,    77.59,     6, tdr1v9",
            "12.93,    77.62,     6, tdr1w3",
            "0,        0,         1, s",
            "-90,      -180,      4, 0000",
            "90,       180,       4, zzzz",
    })
    void encodesReferencePoints(double lat, double lng, int precision, String expected) {
        assertThat(GeoHash.encode(lat, lng, precision)).isEqualTo(expected);
    }

    @Test
    void decodedBoxContainsThePoint() {
        GeoHash.Box box = GeoHash.decode("tdr1v9");
        assertThat(box.contains(12.9716, 77.5946)).isTrue();
        assertThat(box.maxLat() - box.minLat()).isCloseTo(0.0055, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(box.maxLng() - box.minLng()).isCloseTo(0.0110, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(GeoHash.encode(box.centerLat(), box.centerLng(), 6)).isEqualTo("tdr1v9");
    }

    @Test
    void neighboursMatchTheDocumentedSearchCells() {
        // docs/data-format.md §7, computed independently: NW, N, NE, W, C, E, SW, S, SE.
        assertThat(GeoHash.withNeighbours("tdr1v9"))
                .containsExactly("tdr1v6", "tdr1vd", "tdr1vf", "tdr1v3", "tdr1v9", "tdr1vc", "tdr1v2", "tdr1v8", "tdr1vb");
    }

    @Test
    void neighboursWrapAroundTheAntimeridian() {
        String east = GeoHash.encode(0.5, 179.99, 4);
        assertThat(GeoHash.withNeighbours(east)).hasSize(9)
                .anyMatch(c -> GeoHash.decode(c).minLng() < 0); // a western-hemisphere neighbour
    }

    @Test
    void neighboursStopAtThePole() {
        assertThat(GeoHash.withNeighbours(GeoHash.encode(89.99, 10, 3))).hasSize(6); // no row beyond the pole
    }

    @Test
    void validation() {
        assertThat(GeoHash.isValid("tdr1v9")).isTrue();
        assertThat(GeoHash.isValid("smoke1")).isFalse(); // "o" is not in the alphabet
        assertThat(GeoHash.isValid("TDR1V9")).isFalse();
        assertThat(GeoHash.isValid("")).isFalse();
        assertThat(GeoHash.isValid("tdr1v9tdr1v9t")).isFalse(); // 13 characters
        assertThatThrownBy(() -> GeoHash.encode(91, 0, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoHash.encode(Double.NaN, 0, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoHash.encode(0, 0, 13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoHash.decode("abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
