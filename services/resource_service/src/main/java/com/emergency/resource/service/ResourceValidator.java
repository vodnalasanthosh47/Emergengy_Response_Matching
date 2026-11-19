package com.emergency.resource.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.emergency.common.model.Category;
import com.emergency.common.resource.Capacity;
import com.emergency.resource.exception.InvalidResourceException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-type rules for a new resource's capacity and details. They mirror the request side
 * (DetailsValidator), because matching compares a request's amount with
 * {@code capacity.remaining} in the same unit:
 *
 * <pre>
 * WATER      L       decimal &gt; 0   details {}
 * FOOD       MEALS   whole &gt; 0     details {}
 * MEDICINE   UNITS   whole &gt; 0     details { name }
 * TRANSPORT  PEOPLE  whole &gt; 0     details {}
 * SHELTER    PEOPLE  whole &gt; 0     details {}
 * </pre>
 */
@Component
public class ResourceValidator {

    static final int MAX_TEXT = 200;

    /** Returns the capacity with {@code remaining} and {@code unit} filled in. */
    public Capacity capacity(Category type, Capacity capacity) {
        if (capacity == null) {
            throw new InvalidResourceException("capacity is required");
        }
        boolean whole = type != Category.WATER;
        BigDecimal total = amount("capacity.total", capacity.total(), whole);
        BigDecimal remaining = capacity.remaining() == null
                ? total
                : amount("capacity.remaining", capacity.remaining(), whole);
        if (remaining.compareTo(total) > 0) {
            throw new InvalidResourceException("capacity.remaining must not exceed capacity.total");
        }
        String unit = Capacity.unitFor(type);
        if (capacity.unit() != null && !capacity.unit().equals(unit)) {
            throw new InvalidResourceException("capacity.unit must be \"" + unit + "\" for " + type);
        }
        return new Capacity(total, remaining, unit);
    }

    /** Returns the normalised details: {@code {"name": ...}} for MEDICINE, {@code {}} otherwise. */
    public JsonNode details(Category type, JsonNode details) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (details == null || details.isNull()) {
            if (type == Category.MEDICINE) {
                throw new InvalidResourceException("details.name is required for MEDICINE");
            }
            return out;
        }
        if (!details.isObject()) {
            throw new InvalidResourceException("details must be an object");
        }
        for (String field : details.propertyNames()) {
            if (type != Category.MEDICINE || !field.equals("name")) {
                throw new InvalidResourceException("details." + field + " is not a field of " + type + " details");
            }
        }
        if (type == Category.MEDICINE) {
            JsonNode name = details.get("name");
            if (name == null || !name.isString() || name.stringValue().isBlank()) {
                throw new InvalidResourceException("details.name must be a non-empty string");
            }
            String s = name.stringValue().strip();
            if (s.length() > MAX_TEXT) {
                throw new InvalidResourceException("details.name must be at most " + MAX_TEXT + " characters");
            }
            out.put("name", s);
        }
        return out;
    }

    /**
     * Upper bound for any capacity. Storage keeps numbers as 64-bit floats, so an amount
     * such as 1e400 cannot be stored at all (it used to fail with a 500); a million litres,
     * meals, units or people is far above any real resource.
     */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");
    /** WATER may be fractional, to the millilitre; more digits would not survive storage exactly. */
    static final int MAX_DECIMALS = 3;

    // scale() > 0 means the client wrote a fraction (e.g. 20.5 or 20.0); the request side
    // rejects 20.0 for whole-number amounts too.
    private static BigDecimal amount(String field, BigDecimal value, boolean whole) {
        if (value == null) {
            throw new InvalidResourceException(field + " is required");
        }
        if (value.signum() <= 0) {
            throw new InvalidResourceException(field + " must be > 0");
        }
        if (value.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidResourceException(field + " must be at most " + MAX_AMOUNT);
        }
        if (whole && value.scale() > 0) {
            throw new InvalidResourceException(field + " must be a whole number");
        }
        if (value.scale() > MAX_DECIMALS) {
            throw new InvalidResourceException(field + " must have at most " + MAX_DECIMALS + " decimal places");
        }
        // 1e3 arrives as 1E+3 (negative scale); store it as plain 1000.
        return value.scale() < 0 ? value.setScale(0) : value;
    }
}
