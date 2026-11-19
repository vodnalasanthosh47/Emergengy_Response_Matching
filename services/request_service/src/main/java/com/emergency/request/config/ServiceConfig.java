package com.emergency.request.config;

import java.time.Clock;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.storage.StorageClient;
import com.emergency.request.domain.DetailsValidator;
import com.emergency.request.domain.RequestEventPublisher;
import com.emergency.request.domain.RequestRepository;
import com.emergency.request.domain.RequestService;
import com.emergency.request.messaging.RabbitRequestEventPublisher;
import com.emergency.request.storage.StorageRequestRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the plain domain classes to their adapters. The domain classes carry no Spring
 * annotations on purpose: business logic stays independent of the framework (spec §6).
 */
@Configuration
public class ServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    StorageClient storageClient(RequestServiceProperties props, JsonMapper json) {
        return new StorageClient(props.coordinatorUrl(), props.storageTimeout(), json);
    }

    @Bean
    RequestRepository requestRepository(StorageClient storage) {
        return new StorageRequestRepository(storage);
    }

    @Bean
    RequestEventPublisher requestEventPublisher(RabbitTemplate rabbit, JsonMapper json, Clock clock,
            RequestServiceProperties props) {
        return new RabbitRequestEventPublisher(rabbit, json, clock, props.publishConfirmTimeout());
    }

    @Bean
    RequestService requestService(RequestRepository repository, RequestEventPublisher events, Clock clock,
            RequestServiceProperties props) {
        return new RequestService(repository, events, new DetailsValidator(), clock, props.geohashPrecision());
    }
}
