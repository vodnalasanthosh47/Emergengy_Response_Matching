package com.emergency.matching.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.events.EventTopology;

/**
 * The RabbitMQ topology this service owns (spec §4.5). Spring declares it on the first
 * connection; re-declaring with the same settings is a no-op.
 *
 * <pre>
 * emergency.events (topic) ──request.created.*──► matching.request-created ──► listener
 *                                                      │ rejected (nack, no requeue)
 *                                                      ▼
 *                              emergency.dlx (fanout) ──► matching.request-created.dlq
 * </pre>
 *
 * The events exchange is declared here too (same settings as the Request Service), so the
 * binding works whichever service starts first.
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EventTopology.EXCHANGE).durable(true).build();
    }

    /** Fanout: every rejected message goes to the DLQ, whatever its routing key. */
    @Bean
    FanoutExchange deadLetterExchange() {
        return ExchangeBuilder.fanoutExchange(EventTopology.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue matchingQueue() {
        return QueueBuilder.durable(EventTopology.MATCHING_QUEUE)
                .deadLetterExchange(EventTopology.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(EventTopology.MATCHING_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding matchingBinding(Queue matchingQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(matchingQueue).to(eventsExchange).with(EventTopology.REQUEST_CREATED_BINDING);
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }
}
