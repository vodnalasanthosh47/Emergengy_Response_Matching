package com.emergency.request.messaging;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.rabbitmq.client.ShutdownSignalException;

import com.emergency.common.events.EventTopology;
import com.emergency.common.events.RequestCreatedEvent;
import com.emergency.common.request.RequestView;
import com.emergency.request.domain.DependencyUnavailableException;
import com.emergency.request.domain.RequestEventPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code RequestCreated} to RabbitMQ (spec §4.5,
 * contracts/events/request-created.v1.json):
 *
 * <ul>
 * <li>exchange {@code emergency.events}, routing key {@code request.created.<type>};</li>
 * <li>persistent message, so a queued event survives a broker restart;</li>
 * <li>publisher confirm: we wait until the broker has taken responsibility for the
 *     message before telling the client "201 Created". No confirm in time = 503.</li>
 * </ul>
 */
public class RabbitRequestEventPublisher implements RequestEventPublisher {

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration confirmTimeout;

    public RabbitRequestEventPublisher(RabbitTemplate rabbit, JsonMapper json, Clock clock, Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.json = json;
        this.clock = clock;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publishCreated(RequestView request) {
        RequestCreatedEvent event = RequestCreatedEvent.of(request, clock.instant());
        Message message = new Message(json.writeValueAsBytes(event), properties(event));
        String routingKey = EventTopology.requestCreatedRoutingKey(request.type());
        try {
            // invoke() runs both calls on one channel, so the confirm we wait for is this message's.
            rabbit.invoke(channel -> {
                channel.send(EventTopology.EXCHANGE, routingKey, message);
                channel.waitForConfirmsOrDie(confirmTimeout.toMillis());
                return null;
            });
        } catch (AmqpException | ShutdownSignalException e) {
            // ShutdownSignalException: the broker closed the channel, e.g. because the
            // exchange does not exist. Not wrapped by Spring inside invoke(), so caught here too.
            throw new DependencyUnavailableException("message broker unavailable: " + e.getMessage(), e);
        }
    }

    private MessageProperties properties(RequestCreatedEvent event) {
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        p.setContentEncoding("UTF-8");
        p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        p.setMessageId(event.eventId());   // lets a consumer recognise a redelivery
        p.setType(RequestCreatedEvent.TYPE);
        p.setTimestamp(Date.from(event.occurredAt()));
        return p;
    }
}
