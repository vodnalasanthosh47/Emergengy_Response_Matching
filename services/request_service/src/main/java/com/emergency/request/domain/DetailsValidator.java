package com.emergency.request.domain;

import java.util.Map;
import java.util.Set;

import com.emergency.common.model.Category;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates a request's {@code details} for its type and returns a normalised copy
 * (fields in a fixed order, default unit filled in). The rules are the per-type schemas
 * in contracts/openapi/request-service-api.yaml (RequestDetails):
 *
 * <pre>
 * WATER      { quantity: number &gt; 0, unit?: "L" }
 * FOOD       { quantity: int &gt; 0,    unit?: "MEALS" }
 * MEDICINE   { name: string, quantity: int &gt; 0 }
 * TRANSPORT  { people: int &gt; 0, start?: {lat,lng}, destination?: {lat,lng} }
 * SHELTER    { people: int &gt; 0 }
 * </pre>
 *
 * Unknown fields are rejected, so a typo such as {@code quantitiy} is an error rather
 * than a request for nothing.
 */
public class DetailsValidator {

    static final int MAX_TEXT = 200;

    private static final Map<Category, Set<String>> ALLOWED = Map.of(
            Category.WATER, Set.of("quantity", "unit"),
            Category.FOOD, Set.of("quantity", "unit"),
            Category.MEDICINE, Set.of("name", "quantity"),
            Category.TRANSPORT, Set.of("people", "start", "destination"),
            Category.SHELTER, Set.of("people"));

    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    /** @throws InvalidRequestException with a message naming the offending field */
    public JsonNode validate(Category type, JsonNode details) {
        if (details == null || !details.isObject()) {
            throw new InvalidRequestException("details must be an object");
        }
        for (String name : details.propertyNames()) {
            if (!ALLOWED.get(type).contains(name)) {
                throw new InvalidRequestException("details." + name + " is not a field of " + type + " details (allowed: "
                        + String.join(", ", ALLOWED.get(type).stream().sorted().toList()) + ")");
            }
        }
        ObjectNode out = nodes.objectNode();
        switch (type) {
            case WATER -> {
                out.set("quantity", positiveNumber(details, "quantity"));
                out.put("unit", unit(details, "L"));
            }
            case FOOD -> {
                out.put("quantity", positiveInt(details, "quantity"));
                out.put("unit", unit(details, "MEALS"));
            }
            case MEDICINE -> {
                out.put("name", text(details, "name"));
                out.put("quantity", positiveInt(details, "quantity"));
            }
            case TRANSPORT -> {
                out.put("people", positiveInt(details, "people"));
                optionalLocation(details, "start", out);
                optionalLocation(details, "destination", out);
            }
            case SHELTER -> out.put("people", positiveInt(details, "people"));
        }
        return out;
    }

    private static JsonNode positiveNumber(JsonNode details, String field) {
        JsonNode v = details.get(field);
        if (v == null || v.isNull()) {
            throw new InvalidRequestException("details." + field + " is required");
        }
        if (!v.isNumber() || !Double.isFinite(v.doubleValue()) || v.doubleValue() <= 0) {
            throw new InvalidRequestException("details." + field + " must be a number > 0");
        }
        return v; // kept as sent, so 20 stays an integer and 2.5 a decimal
    }

    private static int positiveInt(JsonNode details, String field) {
        JsonNode v = details.get(field);
        if (v == null || v.isNull()) {
            throw new InvalidRequestException("details." + field + " is required");
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() <= 0) {
            throw new InvalidRequestException("details." + field + " must be a whole number > 0");
        }
        return v.intValue();
    }

    private static String text(JsonNode details, String field) {
        JsonNode v = details.get(field);
        if (v == null || !v.isString() || v.stringValue().isBlank()) {
            throw new InvalidRequestException("details." + field + " must be a non-empty string");
        }
        String s = v.stringValue().strip();
        if (s.length() > MAX_TEXT) {
            throw new InvalidRequestException("details." + field + " must be at most " + MAX_TEXT + " characters");
        }
        return s;
    }

    /** The unit is fixed per type; clients may omit it or send exactly that unit. */
    private static String unit(JsonNode details, String expected) {
        JsonNode v = details.get("unit");
        if (v == null || v.isNull()) {
            return expected;
        }
        if (!v.isString() || !v.stringValue().equals(expected)) {
            throw new InvalidRequestException("details.unit must be \"" + expected + "\"");
        }
        return expected;
    }

    private void optionalLocation(JsonNode details, String field, ObjectNode out) {
        JsonNode v = details.get(field);
        if (v == null || v.isNull()) {
            return;
        }
        if (!v.isObject() || v.size() != 2 || !v.path("lat").isNumber() || !v.path("lng").isNumber()) {
            throw new InvalidRequestException("details." + field + " must be {\"lat\": number, \"lng\": number}");
        }
        double lat = v.get("lat").doubleValue();
        double lng = v.get("lng").doubleValue();
        if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
            throw new InvalidRequestException("details." + field + " has coordinates out of range");
        }
        ObjectNode loc = out.putObject(field);
        loc.put("lat", lat);
        loc.put("lng", lng);
    }
}
