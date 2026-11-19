package com.emergency.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.emergency.common.model.Category;

import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class JsonStrictnessTest {

    record Sample(Double lat, BigDecimal total, Integer count, Boolean flag, Category type, Instant at) {
    }

    private final JsonMapper lenient = JsonMapper.builder().build();
    private final JsonMapper strict = JsonStrictness.rejectNumbersAsText(JsonMapper.builder()).build();

    @Test
    void jacksonsDefaultAcceptsNumbersAsText() {
        // The behaviour we are switching off: "500" quietly becomes 500.
        assertThat(lenient.readValue("{\"total\":\"500\"}", Sample.class).total()).isEqualByComparingTo("500");
    }

    @Test
    void realNumbersBooleansEnumsAndDatesStillWork() {
        Sample s = strict.readValue("""
                {"lat":12.97,"total":500,"count":3,"flag":true,"type":"WATER","at":"2026-11-19T10:15:00Z"}""", Sample.class);
        assertThat(s).isEqualTo(new Sample(12.97, new BigDecimal("500"), 3, true, Category.WATER,
                Instant.parse("2026-11-19T10:15:00Z")));
    }

    @Test
    void numbersAndBooleansSentAsTextAreRejected() {
        for (String json : new String[] {"{\"lat\":\"12.97\"}", "{\"total\":\"500\"}", "{\"count\":\"3\"}", "{\"flag\":\"true\"}"}) {
            assertThatThrownBy(() -> strict.readValue(json, Sample.class)).as(json).isInstanceOf(MismatchedInputException.class);
        }
    }
}
