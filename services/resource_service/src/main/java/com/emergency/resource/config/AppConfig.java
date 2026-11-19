package com.emergency.resource.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.storage.StorageClient;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    StorageClient storageClient(ResourceServiceProperties props, JsonMapper json) {
        return new StorageClient(props.coordinatorUrl(), props.storageTimeout(), json);
    }
}
