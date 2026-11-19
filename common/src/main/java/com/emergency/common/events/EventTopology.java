package com.emergency.common.events;

import java.util.Locale;

import com.emergency.common.model.Category;

/**
 * RabbitMQ names shared by publisher and consumer (spec §4.5), kept in one place so
 * the Request and Matching services cannot disagree on them.
 *
 * <pre>
 * Request Service ──publish──► exchange emergency.events (topic, durable)
 *                              routing key request.created.&lt;type&gt;
 *                                    │ binding request.created.*
 *                                    ▼
 *                              queue matching.request-created ──► Matching Service
 *                                    │ rejected / failed
 *                                    ▼
 *                              emergency.dlx ──► matching.request-created.dlq
 * </pre>
 *
 * The Request Service declares only the exchange; the Matching Service declares its
 * queue, binding and dead-letter queue.
 */
public final class EventTopology {

    public static final String EXCHANGE = "emergency.events";
    public static final String REQUEST_CREATED_BINDING = "request.created.*";
    public static final String MATCHING_QUEUE = "matching.request-created";
    public static final String DEAD_LETTER_EXCHANGE = "emergency.dlx";
    public static final String MATCHING_DEAD_LETTER_QUEUE = "matching.request-created.dlq";

    private EventTopology() {
    }

    /** {@code request.created.water}, {@code request.created.food}, ... */
    public static String requestCreatedRoutingKey(Category type) {
        return "request.created." + type.name().toLowerCase(Locale.ROOT);
    }
}
