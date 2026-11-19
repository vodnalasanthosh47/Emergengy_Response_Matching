package com.emergency.common.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.emergency.common.events.EventTopology;
import com.emergency.common.events.RequestCreatedEvent;
import com.emergency.common.model.Category;
import com.emergency.common.model.Location;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The stored and published JSON must look exactly like docs/data-format.md §5 and the event contract. */
class RequestJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final RequestDocument doc = new RequestDocument(
            "f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d", Category.WATER, new Location(12.9716, 77.5946), "tdr1v9",
            RequestStatus.PENDING, new Requester("Ravi Kumar", "+91-90000-00000"),
            json.readTree("{\"quantity\":20,\"unit\":\"L\"}"), null,
            Instant.parse("2026-11-19T10:15:00.112Z"), Instant.parse("2026-11-19T10:15:00.112Z"));

    @Test
    void documentSerialisesAsInTheDataFormat() {
        JsonNode n = json.valueToTree(doc);
        assertThat(n.propertyNames()).containsExactly("requestId", "type", "location", "geoCell", "status", "requester",
                "details", "assignedResourceId", "createdAt", "updatedAt");
        assertThat(n.get("location").propertyNames()).as("no derived properties such as 'valid'").containsExactly("lat", "lng");
        assertThat(n.get("createdAt").stringValue()).isEqualTo("2026-11-19T10:15:00.112Z"); // ISO-8601, not a number
        assertThat(n.get("type").stringValue()).isEqualTo("WATER");
        assertThat(n.get("assignedResourceId").isNull()).isTrue();
        assertThat(n.has("version")).isFalse(); // the version lives in the storage record
        assertThat(n.get("details").get("quantity").isIntegralNumber()).isTrue();
        assertThat(json.treeToValue(n, RequestDocument.class)).isEqualTo(doc);
    }

    @Test
    void eventMatchesTheContract() {
        RequestCreatedEvent event = RequestCreatedEvent.of(RequestView.of(doc, 1), Instant.parse("2026-11-19T10:15:00.120Z"));
        JsonNode n = json.valueToTree(event);
        assertThat(n.propertyNames()).containsExactly("eventId", "eventType", "schemaVersion", "occurredAt", "request");
        assertThat(n.get("eventType").stringValue()).isEqualTo("RequestCreated");
        assertThat(n.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(n.get("request").get("version").longValue()).isEqualTo(1);
        assertThat(n.get("request").get("geoCell").stringValue()).isEqualTo("tdr1v9");
        assertThat(EventTopology.requestCreatedRoutingKey(Category.WATER)).isEqualTo("request.created.water");
        assertThat(json.treeToValue(n, RequestCreatedEvent.class)).isEqualTo(event);
    }

    @Test
    void matchedToSetsStatusAndAssignmentTogether() {
        Instant later = Instant.parse("2026-11-19T10:16:00Z");
        RequestDocument matched = doc.matchedTo("res_tdr1v9_8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77", later);
        assertThat(matched.status()).isEqualTo(RequestStatus.MATCHED);
        assertThat(matched.assignedResourceId()).isEqualTo("res_tdr1v9_8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77");
        assertThat(matched.updatedAt()).isEqualTo(later);
        assertThat(matched).usingRecursiveComparison().ignoringFields("status", "assignedResourceId", "updatedAt")
                .isEqualTo(doc);
    }
}
