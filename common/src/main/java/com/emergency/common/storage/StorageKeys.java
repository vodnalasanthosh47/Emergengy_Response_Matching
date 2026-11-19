package com.emergency.common.storage;

import java.util.regex.Pattern;

import com.emergency.common.geo.GeoHash;

/**
 * Builds and parses storage keys (docs/data-format.md §2). The coordinator derives the
 * shard from the key, and rejects keys of any other shape:
 *
 * <pre>
 * request:{requestId}          shard = crc32(requestId) mod 3
 * resource:{geoCell}:{uuid}    shard = crc32(geoCell)   mod 3
 * </pre>
 *
 * Clients see a resource as {@code res_{geoCell}_{uuid}}; a geohash has no "_" and a UUID
 * only "-", so that ID converts to and from the key unambiguously.
 */
public final class StorageKeys {

    public static final String REQUEST = "request";
    public static final String RESOURCE = "resource";

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]+$");

    private StorageKeys() {
    }

    /** {@code request:{requestId}}. */
    public static String request(String requestId) {
        requireId(requestId, "requestId");
        return REQUEST + ":" + requestId;
    }

    /** {@code resource:{geoCell}:{uuid}}. */
    public static String resource(String geoCell, String uuid) {
        if (!GeoHash.isValid(geoCell)) {
            throw new IllegalArgumentException("geoCell must be a lower-case geohash, got " + geoCell);
        }
        requireId(uuid, "resource uuid");
        return RESOURCE + ":" + geoCell + ":" + uuid;
    }

    /** {@code res_{geoCell}_{uuid}}, the resource ID clients see. */
    public static String resourceId(String geoCell, String uuid) {
        resource(geoCell, uuid); // validates both parts
        return "res_" + geoCell + "_" + uuid;
    }

    /** {@code res_tdr1v9_8a7c...} → {@code resource:tdr1v9:8a7c...}. */
    public static String resourceIdToKey(String resourceId) {
        String[] parts = resourceId == null ? new String[0] : resourceId.split("_", 3);
        if (parts.length != 3 || !parts[0].equals("res")) {
            throw new IllegalArgumentException("resourceId must look like res_{geoCell}_{uuid}, got " + resourceId);
        }
        return resource(parts[1], parts[2]);
    }

    /** {@code resource:tdr1v9:8a7c...} → {@code res_tdr1v9_8a7c...}. */
    public static String keyToResourceId(String key) {
        String[] parts = key == null ? new String[0] : key.split(":", 3);
        if (parts.length != 3 || !parts[0].equals(RESOURCE)) {
            throw new IllegalArgumentException("not a resource key: " + key);
        }
        return resourceId(parts[1], parts[2]);
    }

    private static void requireId(String id, String what) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(what + " must be letters, digits and '-', got " + id);
        }
    }
}
