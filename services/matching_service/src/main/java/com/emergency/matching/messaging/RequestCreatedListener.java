package com.emergency.matching.messaging;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.emergency.common.events.EventTopology;
import com.emergency.common.events.RematchRequestedEvent;
import com.emergency.common.events.RequestCreatedEvent;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.MatchOutcome;
import com.emergency.matching.domain.MatchingService;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the matching queue and runs the matching algorithm for each message. Two
 * message types arrive there:
 *
 * <ul>
 * <li>{@code RequestCreated} (contracts/events/request-created.v1.json), from the Request
 *     Service when a request is stored;</li>
 * <li>{@code RematchRequested} (contracts/events/rematch-requested.v1.json), from this
 *     service's rematch sweeper for a request that is still waiting.</li>
 * </ul>
 *
 * Both are handled the same way. Acknowledgement is manual (spec §4.5):
 *
 * <ul>
 * <li><b>ack</b> once the attempt has a final outcome: MATCHED, UNMATCHED, not matchable
 *     (e.g. already matched: a redelivery) or the request no longer exists;</li>
 * <li><b>retry</b> in place, a few times with a pause, while storage is unavailable or the
 *     request keeps changing under us;</li>
 * <li><b>nack without requeue</b> otherwise: the broker moves the message to the dead-letter
 *     queue, where it can be inspected (and the request matched later with POST /match).</li>
 * </ul>
 *
 * The event is only a trigger: only its request ID is used, and the request is re-read from
 * storage, because the snapshot in the event may be stale.
 */
public class RequestCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(RequestCreatedListener.class);

    private final MatchingService matching;
    private final JsonMapper json;
    private final int attempts;
    private final Duration retryDelay;

    public RequestCreatedListener(MatchingService matching, JsonMapper json, int attempts, Duration retryDelay) {
        this.matching = matching;
        this.json = json;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
    }

    @RabbitListener(queues = EventTopology.MATCHING_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String requestId;
        try {
            requestId = requestIdOf(message);
        } catch (RuntimeException e) {
            log.error("malformed event (message-id {}), sent to the DLQ: {}",
                    message.getMessageProperties().getMessageId(), e.getMessage());
            channel.basicNack(tag, false, false);
            return;
        }

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                MatchOutcome outcome = matching.match(requestId);
                log.info("request {} handled: {}", requestId, outcome.getClass().getSimpleName());
                channel.basicAck(tag, false);
                return;
            } catch (DependencyUnavailableException | ConcurrentUpdateException e) {
                log.warn("request {}: attempt {}/{} failed: {}", requestId, attempt, attempts, e.getMessage());
                if (attempt < attempts && !pause()) {
                    break;
                }
            } catch (RuntimeException e) {
                log.error("request {}: unexpected error", requestId, e);
                break;
            }
        }
        log.error("event for request {} sent to the DLQ ({}); match it later with POST /match/{}", requestId,
                EventTopology.MATCHING_DEAD_LETTER_QUEUE, requestId);
        channel.basicNack(tag, false, false);
    }

    /**
     * The request ID of a v1 RequestCreated or RematchRequested event; anything else is
     * malformed. Only the ID is used: the request itself is re-read from storage.
     */
    private String requestIdOf(Message message) {
        JsonNode event = json.readTree(message.getBody());
        String type = event.path("eventType").asString(null);
        int version = event.path("schemaVersion").asInt(-1);
        JsonNode idNode;
        if (RequestCreatedEvent.TYPE.equals(type) && version == RequestCreatedEvent.SCHEMA_VERSION) {
            idNode = event.path("request").path("requestId");
        } else if (RematchRequestedEvent.TYPE.equals(type) && version == RematchRequestedEvent.SCHEMA_VERSION) {
            idNode = event.path("requestId");
        } else {
            throw new IllegalArgumentException("not a RequestCreated v" + RequestCreatedEvent.SCHEMA_VERSION
                    + " or RematchRequested v" + RematchRequestedEvent.SCHEMA_VERSION + " event");
        }
        String id = idNode.asString(null);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("event has no request.requestId");
        }
        return id;
    }

    /** @return false if the thread was interrupted (shutting down) */
    private boolean pause() {
        try {
            Thread.sleep(retryDelay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
