package com.emergency.matching.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.storage.StorageClient;
import com.emergency.matching.domain.CandidateRanking;
import com.emergency.matching.domain.MatchingService;
import com.emergency.matching.domain.RequestStore;
import com.emergency.matching.domain.ResourceStore;
import com.emergency.matching.messaging.RequestCreatedListener;
import com.emergency.matching.storage.StorageRequestStore;
import com.emergency.matching.storage.StorageResourceStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the plain domain classes to their adapters. As in the Request Service, the domain
 * classes carry no Spring annotations, so the matching rules stay independent of the
 * framework (spec §6).
 */
@Configuration
public class ServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    StorageClient storageClient(MatchingServiceProperties props, JsonMapper json) {
        return new StorageClient(props.coordinatorUrl(), props.storageTimeout(), json);
    }

    @Bean
    RequestStore requestStore(StorageClient storage) {
        return new StorageRequestStore(storage);
    }

    @Bean
    ResourceStore resourceStore(StorageClient storage) {
        return new StorageResourceStore(storage);
    }

    @Bean
    MatchingService matchingService(RequestStore requests, ResourceStore resources, Clock clock,
            MatchingServiceProperties props) {
        return new MatchingService(requests, resources, new CandidateRanking(), clock, props.candidateLimit());
    }

    @Bean
    RequestCreatedListener requestCreatedListener(MatchingService matching, JsonMapper json, MatchingServiceProperties props) {
        return new RequestCreatedListener(matching, json, props.eventAttempts(), props.eventRetryDelay());
    }
}
