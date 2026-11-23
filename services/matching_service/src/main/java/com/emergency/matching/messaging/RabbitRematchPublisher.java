package com.emergency.matching.messaging;

import java.time.Clock;
import java.util.Date;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.emergency.common.events.EventTopology;
import com.emergency.common.events.RematchRequestedEvent;
import com.emergency.common.model.Category;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.RematchPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code RematchRequested} (contracts/events/rematch-requested.v1.json) to
 * {@code emergency.events} with routing key {@code request.rematch.<type>}, which is bound
 * to this service's own matching queue.
 *
 * <p>Persistent, but without publisher confirms (unlike RequestCreated): if a message is
 * lost, the request is still waiting in storage and the next sweep publishes it again.
 */
public class RabbitRematchPublisher implements RematchPublisher {

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final Clock clock;

    public RabbitRematchPublisher(RabbitTemplate rabbit, JsonMapper json, Clock clock) {
        this.rabbit = rabbit;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void publish(String requestId, Category type, RematchRequestedEvent.Reason reason) {
        RematchRequestedEvent event = RematchRequestedEvent.of(requestId, type, reason, clock.instant());
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        p.setContentEncoding("UTF-8");
        p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        p.setMessageId(event.eventId());
        p.setType(RematchRequestedEvent.TYPE);
        p.setTimestamp(Date.from(event.occurredAt()));
        try {
            rabbit.send(EventTopology.EXCHANGE, EventTopology.requestRematchRoutingKey(type),
                    new Message(json.writeValueAsBytes(event), p));
        } catch (AmqpException e) {
            throw new DependencyUnavailableException("message broker unavailable: " + e.getMessage(), e);
        }
    }
}
