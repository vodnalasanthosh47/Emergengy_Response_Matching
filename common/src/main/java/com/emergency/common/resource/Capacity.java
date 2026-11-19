package com.emergency.common.resource;

import java.math.BigDecimal;

import com.emergency.common.model.Category;

/**
 * How much a resource can still give. BigDecimal rather than double: claims subtract
 * amounts exactly, and 500 is stored as 500, not 500.0.
 *
 * @param unit fixed per type: L, MEALS, UNITS or PEOPLE (see {@link #unitFor})
 */
public record Capacity(BigDecimal total, BigDecimal remaining, String unit) {

    public static String unitFor(Category type) {
        return switch (type) {
            case WATER -> "L";
            case FOOD -> "MEALS";
            case MEDICINE -> "UNITS";
            case TRANSPORT, SHELTER -> "PEOPLE";
        };
    }
}
