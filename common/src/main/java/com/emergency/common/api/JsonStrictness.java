package com.emergency.common.api;

import java.util.List;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * One JSON input rule for every service: numbers and booleans must be sent as JSON
 * numbers and booleans. By default Jackson silently turns {@code "500"} into 500; with
 * this rule {@code {"total": "500"}} is a 400, the same as any other wrong type, so all
 * services accept exactly the same input. Text that is naturally text (enums such as
 * {@code "WATER"}, dates) is not affected.
 *
 * <p>Applied by each service's JacksonConfig through a JsonMapperBuilderCustomizer.
 */
public final class JsonStrictness {

    private JsonStrictness() {
    }

    public static JsonMapper.Builder rejectNumbersAsText(JsonMapper.Builder builder) {
        for (LogicalType type : List.of(LogicalType.Integer, LogicalType.Float, LogicalType.Boolean)) {
            builder.withCoercionConfig(type, config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
        }
        return builder;
    }
}
