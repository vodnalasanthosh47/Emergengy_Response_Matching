package com.emergency.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.emergency.common.model.Category;
import com.emergency.common.resource.Capacity;
import com.emergency.resource.exception.InvalidResourceException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResourceValidatorTest {

    private final ResourceValidator validator = new ResourceValidator();
    private final JsonMapper json = JsonMapper.builder().build();

    private static Capacity cap(String total, String remaining, String unit) {
        return new Capacity(total == null ? null : new BigDecimal(total),
                remaining == null ? null : new BigDecimal(remaining), unit);
    }

    private JsonNode node(String text) {
        return json.readTree(text);
    }

    @ParameterizedTest
    @CsvSource({ "WATER, L", "FOOD, MEALS", "MEDICINE, UNITS", "TRANSPORT, PEOPLE", "SHELTER, PEOPLE" })
    void unitAndRemainingAreFilledIn(Category type, String unit) {
        assertThat(validator.capacity(type, cap("40", null, null))).isEqualTo(cap("40", "40", unit));
    }

    @Test
    void explicitRemainingAndMatchingUnitAreKept() {
        assertThat(validator.capacity(Category.FOOD, cap("100", "60", "MEALS"))).isEqualTo(cap("100", "60", "MEALS"));
    }

    @Test
    void waterAllowsDecimals() {
        assertThat(validator.capacity(Category.WATER, cap("20.5", null, null)).remaining()).isEqualByComparingTo("20.5");
    }

    @ParameterizedTest
    @EnumSource(value = Category.class, names = "WATER", mode = EnumSource.Mode.EXCLUDE)
    void otherTypesNeedWholeNumbers(Category type) {
        assertThatThrownBy(() -> validator.capacity(type, cap("2.5", null, null)))
                .isInstanceOf(InvalidResourceException.class).hasMessage("capacity.total must be a whole number");
        assertThatThrownBy(() -> validator.capacity(type, cap("4", "2.0", null)))
                .isInstanceOf(InvalidResourceException.class).hasMessage("capacity.remaining must be a whole number");
    }

    @Test
    void badCapacitiesAreRejected() {
        assertThatThrownBy(() -> validator.capacity(Category.WATER, null)).hasMessage("capacity is required");
        assertThatThrownBy(() -> validator.capacity(Category.WATER, cap(null, null, null)))
                .hasMessage("capacity.total is required");
        assertThatThrownBy(() -> validator.capacity(Category.WATER, cap("0", null, null)))
                .hasMessage("capacity.total must be > 0");
        assertThatThrownBy(() -> validator.capacity(Category.WATER, cap("-5", null, null)))
                .hasMessage("capacity.total must be > 0");
        assertThatThrownBy(() -> validator.capacity(Category.WATER, cap("10", "0", null)))
                .hasMessage("capacity.remaining must be > 0");
        assertThatThrownBy(() -> validator.capacity(Category.WATER, cap("10", "11", null)))
                .hasMessage("capacity.remaining must not exceed capacity.total");
        assertThatThrownBy(() -> validator.capacity(Category.WATER, cap("10", null, "MEALS")))
                .hasMessage("capacity.unit must be \"L\" for WATER");
    }

    @Test
    void medicineNeedsATrimmedName() {
        assertThat(validator.details(Category.MEDICINE, node("{\"name\":\"  Paracetamol \"}")))
                .isEqualTo(node("{\"name\":\"Paracetamol\"}"));
        assertThatThrownBy(() -> validator.details(Category.MEDICINE, null))
                .hasMessage("details.name is required for MEDICINE");
        assertThatThrownBy(() -> validator.details(Category.MEDICINE, node("{}")))
                .hasMessage("details.name must be a non-empty string");
        assertThatThrownBy(() -> validator.details(Category.MEDICINE, node("{\"name\":\" \"}")))
                .hasMessage("details.name must be a non-empty string");
        assertThatThrownBy(() -> validator.details(Category.MEDICINE, node("{\"name\":\"" + "x".repeat(201) + "\"}")))
                .hasMessage("details.name must be at most 200 characters");
        assertThatThrownBy(() -> validator.details(Category.MEDICINE, node("{\"name\":\"a\",\"dose\":1}")))
                .hasMessage("details.dose is not a field of MEDICINE details");
    }

    @ParameterizedTest
    @EnumSource(value = Category.class, names = "MEDICINE", mode = EnumSource.Mode.EXCLUDE)
    void otherTypesHaveEmptyDetails(Category type) {
        assertThat(validator.details(type, null)).isEqualTo(node("{}"));
        assertThat(validator.details(type, node("{}"))).isEqualTo(node("{}"));
        assertThatThrownBy(() -> validator.details(type, node("{\"name\":\"x\"}")))
                .hasMessage("details.name is not a field of " + type + " details");
        assertThatThrownBy(() -> validator.details(type, node("[]"))).hasMessage("details must be an object");
    }
}
