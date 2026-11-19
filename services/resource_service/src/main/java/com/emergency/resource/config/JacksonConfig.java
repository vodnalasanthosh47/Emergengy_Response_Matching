package com.emergency.resource.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emergency.common.api.JsonStrictness;

/**
 * JSON input rules shared by all services (common.api.JsonStrictness): numbers sent as
 * text, such as {"lat": "12.97"}, are a 400. Unknown fields are rejected by
 * spring.jackson.deserialization.fail-on-unknown-properties in application.yml.
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer rejectNumbersAsText() {
        return JsonStrictness::rejectNumbersAsText;
    }
}
