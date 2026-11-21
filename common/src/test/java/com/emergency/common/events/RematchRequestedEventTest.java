package com.emergency.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.emergency.common.model.Category;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The published JSON must match contracts/events/rematch-requested.v1.json exactly. */
class RematchRequestedEventTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serialisesAsInTheContract() {
        RematchRequestedEvent event = RematchRequestedEvent.of("f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d", Category.WATER,
                RematchRequestedEvent.Reason.UNMATCHED, Instant.parse("2026-11-21T09:02:00.004Z"));

        JsonNode n = json.valueToTree(event);

        assertThat(n.propertyNames()).containsExactly("eventId", "eventType", "schemaVersion", "occurredAt", "requestId",
                "type", "reason");
        assertThat(n.get("eventId").stringValue()).hasSize(36);
        assertThat(n.get("eventType").stringValue()).isEqualTo("RematchRequested");
        assertThat(n.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(n.get("occurredAt").stringValue()).isEqualTo("2026-11-21T09:02:00.004Z");
        assertThat(n.get("type").stringValue()).isEqualTo("WATER");
        assertThat(n.get("reason").stringValue()).isEqualTo("UNMATCHED");
        assertThat(json.treeToValue(n, RematchRequestedEvent.class)).isEqualTo(event);
    }

    @Test
    void routingKeyMatchesTheRematchBinding() {
        assertThat(EventTopology.requestRematchRoutingKey(Category.MEDICINE)).isEqualTo("request.rematch.medicine");
        assertThat(EventTopology.REQUEST_REMATCH_BINDING).isEqualTo("request.rematch.*");
    }
}
