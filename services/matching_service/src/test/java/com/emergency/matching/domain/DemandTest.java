package com.emergency.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emergency.common.model.Category;
import com.emergency.matching.support.Fixtures;

class DemandTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "WATER     | {\"quantity\":20,\"unit\":\"L\"}            | 20  |",
            "WATER     | {\"quantity\":2.5,\"unit\":\"L\"}           | 2.5 |",
            "FOOD      | {\"quantity\":10,\"unit\":\"MEALS\"}        | 10  |",
            "MEDICINE  | {\"name\":\"Insulin\",\"quantity\":2}       | 2   | Insulin",
            "TRANSPORT | {\"people\":4,\"destination\":{\"lat\":1.0,\"lng\":2.0}} | 4 |",
            "SHELTER   | {\"people\":6}                             | 6   |",
    })
    void amountComesFromTheTypesField(Category type, String details, String amount, String medicine) {
        Demand d = Demand.of(Fixtures.request(type, details));
        assertThat(d.amount()).isEqualByComparingTo(amount);
        assertThat(d.medicineName()).isEqualTo(medicine);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"WATER | {}", "SHELTER | {\"quantity\":3}", "FOOD | {\"quantity\":0}"})
    void corruptStoredDetailsAreAnError(Category type, String details) {
        assertThatThrownBy(() -> Demand.of(Fixtures.request(type, details))).isInstanceOf(IllegalStateException.class);
    }
}
