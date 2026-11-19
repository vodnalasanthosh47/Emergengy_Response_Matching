package com.emergency.common.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The stored JSON must look exactly like docs/data-format.md §6.1. */
class ResourceJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final ResourceDocument doc = new ResourceDocument(
            "res_tdr1v9_8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77", Category.WATER,
            new Provider("Akshaya NGO", ProviderKind.NGO, "+91-80000-00000"), new Location(12.97, 77.59), "tdr1v9",
            new Capacity(new BigDecimal("500"), new BigDecimal("480"), "L"), json.readTree("{}"),
            ResourceStatus.AVAILABLE, Instant.parse("2026-11-19T10:00:00.250Z"), Instant.parse("2026-11-19T10:00:00.250Z"));

    @Test
    void documentSerialisesAsInTheDataFormat() {
        JsonNode n = json.valueToTree(doc);
        assertThat(n.propertyNames()).containsExactly("resourceId", "type", "provider", "location", "geoCell",
                "capacity", "details", "status", "createdAt", "updatedAt");
        assertThat(n.get("capacity").propertyNames()).containsExactly("total", "remaining", "unit");
        // StorageClient sends valueToTree(value) as text, so check that exact path.
        assertThat(json.writeValueAsString(n.get("capacity")))
                .isEqualTo("{\"total\":500,\"remaining\":480,\"unit\":\"L\"}");
        assertThat(n.get("provider").get("kind").stringValue()).isEqualTo("NGO");
        assertThat(n.get("createdAt").stringValue()).isEqualTo("2026-11-19T10:00:00.250Z");
        assertThat(n.has("version")).isFalse();
        assertThat(json.treeToValue(n, ResourceDocument.class)).isEqualTo(doc);
    }

    @Test
    void decimalCapacityKeepsItsFraction() {
        Capacity c = json.readValue("{\"total\":20.5,\"remaining\":20.5,\"unit\":\"L\"}", Capacity.class);
        assertThat(c.total()).isEqualByComparingTo("20.5");
        assertThat(json.writeValueAsString(c)).isEqualTo("{\"total\":20.5,\"remaining\":20.5,\"unit\":\"L\"}");
    }

    @Test
    void viewAddsTheVersion() {
        JsonNode n = json.valueToTree(ResourceView.of(doc, 3));
        assertThat(n.get("version").longValue()).isEqualTo(3);
        assertThat(n.get("resourceId").stringValue()).isEqualTo(doc.resourceId());
    }

    @Test
    void unitIsFixedPerType() {
        assertThat(Capacity.unitFor(Category.WATER)).isEqualTo("L");
        assertThat(Capacity.unitFor(Category.FOOD)).isEqualTo("MEALS");
        assertThat(Capacity.unitFor(Category.MEDICINE)).isEqualTo("UNITS");
        assertThat(Capacity.unitFor(Category.TRANSPORT)).isEqualTo("PEOPLE");
        assertThat(Capacity.unitFor(Category.SHELTER)).isEqualTo("PEOPLE");
    }

    @Test
    void withCapacityChangesOnlyCapacityStatusAndTime() {
        Instant later = Instant.parse("2026-11-19T11:00:00Z");
        Capacity empty = new Capacity(new BigDecimal("500"), BigDecimal.ZERO, "L");
        ResourceDocument claimed = doc.withCapacity(empty, ResourceStatus.DEPLETED, later);
        assertThat(claimed.capacity()).isEqualTo(empty);
        assertThat(claimed.status()).isEqualTo(ResourceStatus.DEPLETED);
        assertThat(claimed.updatedAt()).isEqualTo(later);
        assertThat(claimed).usingRecursiveComparison().ignoringFields("capacity", "status", "updatedAt").isEqualTo(doc);
    }
}
