package com.emergency.request.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.events.EventTopology;

/**
 * RabbitMQ topology owned by the Request Service: just the exchange (spec §4.5). Spring
 * declares it on the first connection; declaring an existing exchange with the same
 * settings is a no-op, so every instance can do it. The Matching Service declares its own
 * queue, binding and dead-letter queue.
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EventTopology.EXCHANGE).durable(true).build();
    }

    /**
     * Messages are published "mandatory": if no queue is bound yet (the Matching Service has
     * never started), the broker returns the message instead of silently dropping it, and we
     * log it. The request itself is stored; POST /match/{id} can still match it.
     */
    @Bean
    RabbitTemplateCustomizer logUnroutableMessages() {
        return template -> template.setReturnsCallback(returned -> log.warn(
                "event not routed to any queue (is the Matching Service's queue declared?): routingKey={} reply={}",
                returned.getRoutingKey(), returned.getReplyText()));
    }
}
