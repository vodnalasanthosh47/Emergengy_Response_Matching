package com.emergency.common.storage;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /v1/query}: records of {@code collection} whose indexes equal every
 * entry of {@code match}, in any of {@code geoCells} (empty = any cell), sorted by key.
 * A resource query with cells is answered only by the shards owning those cells.
 *
 * @param limit 1–1000; 0 means the coordinator's default (50)
 */
public record StorageQuery(String collection, List<String> geoCells, Map<String, String> match, int limit) {

    public StorageQuery {
        geoCells = geoCells == null ? List.of() : List.copyOf(geoCells);
        match = match == null ? Map.of() : Map.copyOf(match);
    }
}
