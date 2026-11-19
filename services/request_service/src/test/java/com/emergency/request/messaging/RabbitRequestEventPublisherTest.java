package com.emergency.request.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.emergency.common.events.EventTopology;
import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.request.domain.DependencyUnavailableException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Against a real RabbitMQ (Docker): the message must match contracts/events/request-created.v1.json and spec §4.5. */
@Testcontainers(disabledWithoutDocker = true)
class RabbitRequestEventPublisherTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:15:00.120Z"), ZoneOffset.UTC);
    private CachingConnectionFactory connections;
    private RabbitTemplate template;
    private RabbitAdmin admin;

    @BeforeEach
    void setUp() {
        connections = connect(RABBIT.getAmqpPort());
        template = new RabbitTemplate(connections);
        template.setMandatory(true);
        admin = new RabbitAdmin(connections);
    }

    @AfterEach
    void tearDown() {
        admin.deleteExchange(EventTopology.EXCHANGE);
        connections.destroy();
    }

    private CachingConnectionFactory connect(int port) {
        CachingConnectionFactory f = new CachingConnectionFactory(RABBIT.getHost(), port);
        f.setUsername(RABBIT.getAdminUsername());
        f.setPassword(RABBIT.getAdminPassword());
        f.setConnectionTimeout(2000);
        f.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        f.setPublisherReturns(true);
        return f;
    }

    private RequestView request(Category type) {
        return new RequestView("f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d", type, new Location(12.9716, 77.5946), "tdr1v9",
                RequestStatus.PENDING, null, json.readTree("{\"people\":4}"), null,
                Instant.parse("2026-11-19T10:15:00.112Z"), Instant.parse("2026-11-19T10:15:00.112Z"), 1);
    }

    /** Declares the topology the Matching Service will: exchange + a queue bound to request.created.*. */
    private Queue bindTestQueue() {
        TopicExchange exchange = new TopicExchange(EventTopology.EXCHANGE, true, false);
        admin.declareExchange(exchange);
        // Not an AnonymousQueue: that is auto-delete and vanishes after the first receive().
        Queue queue = new Queue("test." + java.util.UUID.randomUUID(), false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(EventTopology.REQUEST_CREATED_BINDING));
        return queue;
    }

    @Test
    void publishesAPersistentConfirmedRequestCreatedEvent() {
        Queue queue = bindTestQueue();
        new RabbitRequestEventPublisher(template, json, clock, Duration.ofSeconds(5)).publishCreated(request(Category.TRANSPORT));

        Message m = template.receive(queue.getName(), 5000);
        assertThat(m).as("event delivered").isNotNull();
        assertThat(m.getMessageProperties().getReceivedRoutingKey()).isEqualTo("request.created.transport");
        assertThat(m.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(m.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(m.getMessageProperties().getType()).isEqualTo("RequestCreated");

        JsonNode body = json.readTree(m.getBody());
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(body.get("eventId").stringValue());
        assertThat(body.get("eventType").stringValue()).isEqualTo("RequestCreated");
        assertThat(body.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(body.get("occurredAt").stringValue()).isEqualTo("2026-11-19T10:15:00.120Z");
        assertThat(body.get("request").get("requestId").stringValue()).isEqualTo("f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d");
        assertThat(body.get("request").get("status").stringValue()).isEqualTo("PENDING");
        assertThat(body.get("request").get("version").longValue()).isEqualTo(1);
        assertThat(body.get("request").get("details").get("people").intValue()).isEqualTo(4);
    }

    @Test
    void everyCategoryUsesItsOwnRoutingKey() {
        Queue queue = bindTestQueue();
        RabbitRequestEventPublisher publisher = new RabbitRequestEventPublisher(template, json, clock, Duration.ofSeconds(5));
        for (Category type : Category.values()) {
            publisher.publishCreated(request(type));
            Message m = template.receive(queue.getName(), 5000);
            assertThat(m.getMessageProperties().getReceivedRoutingKey()).isEqualTo(EventTopology.requestCreatedRoutingKey(type));
        }
    }

    /** No exchange: the broker refuses the publish, so the caller gets 503 instead of a silently lost event. */
    @Test
    void missingExchangeIsUnavailableNotSilentLoss() {
        RabbitRequestEventPublisher publisher = new RabbitRequestEventPublisher(template, json, clock, Duration.ofSeconds(5));
        assertThatThrownBy(() -> publisher.publishCreated(request(Category.WATER)))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void unreachableBrokerIsUnavailable() {
        CachingConnectionFactory nowhere = connect(1);
        try {
            RabbitRequestEventPublisher publisher =
                    new RabbitRequestEventPublisher(new RabbitTemplate(nowhere), json, clock, Duration.ofSeconds(2));
            assertThatThrownBy(() -> publisher.publishCreated(request(Category.WATER)))
                    .isInstanceOf(DependencyUnavailableException.class);
        } finally {
            nowhere.destroy();
        }
    }
}
