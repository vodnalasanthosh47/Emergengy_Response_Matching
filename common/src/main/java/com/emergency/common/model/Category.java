package com.emergency.common.model;

/**
 * The kind of help (spec §3.1). Requests and resources use the same categories; that is
 * what matching joins on. A volunteer or NGO is a provider, not a category.
 */
public enum Category {
    WATER, FOOD, MEDICINE, TRANSPORT, SHELTER
}
