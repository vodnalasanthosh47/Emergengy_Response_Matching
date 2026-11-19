package com.emergency.request.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emergency.common.model.Category;

import tools.jackson.databind.json.JsonMapper;

class DetailsValidatorTest {

    private final DetailsValidator validator = new DetailsValidator();
    private final JsonMapper json = JsonMapper.builder().build();

    /** Valid input → normalised output (fixed field order, default units, trimmed names). */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "WATER     | {\"quantity\":20}                                  | {\"quantity\":20,\"unit\":\"L\"}",
            "WATER     | {\"unit\":\"L\",\"quantity\":2.5}                  | {\"quantity\":2.5,\"unit\":\"L\"}",
            "FOOD      | {\"quantity\":10,\"unit\":\"MEALS\"}               | {\"quantity\":10,\"unit\":\"MEALS\"}",
            "FOOD      | {\"quantity\":3}                                   | {\"quantity\":3,\"unit\":\"MEALS\"}",
            "MEDICINE  | {\"quantity\":2,\"name\":\"  Insulin \"}           | {\"name\":\"Insulin\",\"quantity\":2}",
            "TRANSPORT | {\"people\":4}                                     | {\"people\":4}",
            "TRANSPORT | {\"people\":4,\"destination\":{\"lat\":12.93,\"lng\":77.62}} | {\"people\":4,\"destination\":{\"lat\":12.93,\"lng\":77.62}}",
            "TRANSPORT | {\"people\":1,\"start\":{\"lat\":1,\"lng\":2},\"destination\":null} | {\"people\":1,\"start\":{\"lat\":1.0,\"lng\":2.0}}",
            "SHELTER   | {\"people\":12}                                    | {\"people\":12}",
    })
    void acceptsAndNormalises(Category type, String in, String expected) {
        assertThat(validator.validate(type, json.readTree(in))).isEqualTo(json.readTree(expected));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "WATER     | {}                                   | details.quantity is required",
            "WATER     | {\"quantity\":0}                     | must be a number > 0",
            "WATER     | {\"quantity\":-5}                    | must be a number > 0",
            "WATER     | {\"quantity\":\"20\"}                | must be a number > 0",
            "WATER     | {\"quantity\":20,\"unit\":\"ml\"}    | details.unit must be \"L\"",
            "WATER     | {\"quantitiy\":20}                   | details.quantitiy is not a field of WATER",
            "FOOD      | {\"quantity\":2.5}                   | must be a whole number > 0",
            "FOOD      | {\"quantity\":99999999999}           | must be a whole number > 0",
            "FOOD      | {\"quantity\":5,\"unit\":\"L\"}      | details.unit must be \"MEALS\"",
            "MEDICINE  | {\"quantity\":1}                     | details.name must be a non-empty string",
            "MEDICINE  | {\"name\":\"  \",\"quantity\":1}     | details.name must be a non-empty string",
            "MEDICINE  | {\"name\":\"x\"}                     | details.quantity is required",
            "TRANSPORT | {\"people\":0}                       | details.people must be a whole number > 0",
            "TRANSPORT | {\"people\":2,\"destination\":{\"lat\":100,\"lng\":0}} | details.destination has coordinates out of range",
            "TRANSPORT | {\"people\":2,\"start\":\"home\"}    | details.start must be",
            "TRANSPORT | {\"people\":2,\"start\":{\"lat\":1,\"lng\":2,\"x\":3}} | details.start must be",
            "SHELTER   | {\"people\":2,\"quantity\":3}        | details.quantity is not a field of SHELTER",
            "SHELTER   | []                                   | details must be an object",
            "SHELTER   | null                                 | details must be an object",
    })
    void rejectsWithAClearMessage(Category type, String in, String message) {
        assertThatThrownBy(() -> validator.validate(type, json.readTree(in)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(message);
    }
}
