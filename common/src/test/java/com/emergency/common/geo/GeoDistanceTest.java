package com.emergency.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.Test;

import com.emergency.common.model.Location;

class GeoDistanceTest {

    @Test
    void knownDistances() {
        Location bengaluru = new Location(12.9716, 77.5946);
        Location chennai = new Location(13.0827, 80.2707);
        assertThat(GeoDistance.km(bengaluru, chennai)).isCloseTo(290.2, offset(1.0));
        assertThat(GeoDistance.km(bengaluru, bengaluru)).isZero();
        // One degree of latitude is about 111.2 km everywhere.
        assertThat(GeoDistance.km(new Location(0.0, 0.0), new Location(1.0, 0.0))).isCloseTo(111.2, offset(0.1));
        assertThat(GeoDistance.km(bengaluru, chennai)).isEqualTo(GeoDistance.km(chennai, bengaluru));
    }
}
