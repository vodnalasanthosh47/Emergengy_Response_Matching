package com.emergency.matching.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.emergency.common.geo.GeoHash;
import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.resource.Capacity;
import com.emergency.common.resource.Provider;
import com.emergency.common.resource.ProviderKind;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.StorageKeys;

import tools.jackson.databind.json.JsonMapper;

/** Requests and resources in the documented format (docs/data-format.md §5, §6). */
public final class Fixtures {

    public static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");
    /** Bengaluru, cell tdr1v9 at precision 6. */
    public static final double LAT = 12.9716;
    public static final double LNG = 77.5946;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Fixtures() {
    }

    public static RequestDocument request(Category type, String details) {
        return request(UUID.randomUUID().toString(), type, LAT, LNG, details, RequestStatus.PENDING);
    }

    public static RequestDocument request(String id, Category type, double lat, double lng, String details,
            RequestStatus status) {
        return new RequestDocument(id, type, new Location(lat, lng), GeoHash.encode(lat, lng, 6), status, null,
                JSON.readTree(details), null, T0, T0);
    }

    /** A resource {@code dLat} degrees north of the request point (0.001° ≈ 111 m). */
    public static ResourceDocument resource(Category type, double dLat, String total, String remaining) {
        return resource(type, LAT + dLat, LNG, total, remaining, ResourceStatus.AVAILABLE, null);
    }

    public static ResourceDocument resource(Category type, double lat, double lng, String total, String remaining,
            ResourceStatus status, String medicine) {
        String cell = GeoHash.encode(lat, lng, 6);
        return new ResourceDocument(StorageKeys.resourceId(cell, UUID.randomUUID().toString()), type,
                new Provider("Test NGO", ProviderKind.NGO, null), new Location(lat, lng), cell,
                new Capacity(new BigDecimal(total), new BigDecimal(remaining), Capacity.unitFor(type)),
                medicine == null ? JSON.readTree("{}") : JSON.readTree("{\"name\":\"" + medicine + "\"}"),
                status, T0, T0);
    }
}
