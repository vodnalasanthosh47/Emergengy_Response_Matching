package com.emergency.matching.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.storage.StorageClient;

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
}
