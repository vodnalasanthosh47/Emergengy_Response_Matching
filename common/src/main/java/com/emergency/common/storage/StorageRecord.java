package com.emergency.common.storage;

import java.time.Instant;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * One record as the coordinator returns it (docs/data-format.md §3.2).
 *
 * @param value   the domain document, exactly as written; convert it with
 *                {@link StorageClient#valueAs(StorageRecord, Class)}
 * @param version 1 on create, +1 on every write; pass it back as {@code expectedVersion}
 */
public record StorageRecord(String key, JsonNode value, Map<String, String> indexes, long version, Instant updatedAt) {
}
