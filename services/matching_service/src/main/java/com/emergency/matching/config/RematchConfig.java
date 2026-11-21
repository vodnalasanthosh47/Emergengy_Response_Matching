package com.emergency.matching.config;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;

import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.RematchPublisher;
import com.emergency.matching.domain.RematchSweeper;
import com.emergency.matching.domain.WaitingRequests;
import com.emergency.matching.messaging.RabbitRematchPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the {@link RematchSweeper} every {@code REMATCH_INTERVAL} (default 2 min).
 *
 * <p>Fixed <em>delay</em>, not fixed rate: the next pass starts one interval after the
 * previous one ended, so passes never overlap. The first pass runs one interval after
 * startup. {@code REMATCH_ENABLED=false} switches the sweeper off entirely.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "emergency.rematch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RematchConfig {

    private static final Logger log = LoggerFactory.getLogger(RematchConfig.class);

    @Bean
    RematchPublisher rematchPublisher(RabbitTemplate rabbit, JsonMapper json, Clock clock) {
        return new RabbitRematchPublisher(rabbit, json, clock);
    }

    @Bean
    RematchSweeper rematchSweeper(WaitingRequests waiting, RematchPublisher publisher, Clock clock,
            MatchingServiceProperties props) {
        return new RematchSweeper(waiting, publisher, clock, props.rematch().pendingAge(), props.rematch().batchSize());
    }

    /** Registers the periodic pass with Spring's task scheduler. */
    @Bean
    SchedulingConfigurer rematchSchedule(RematchSweeper sweeper, MatchingServiceProperties props) {
        Duration interval = props.rematch().interval();
        if (!interval.isPositive()) {
            throw new IllegalArgumentException("emergency.rematch.interval (REMATCH_INTERVAL) must be positive, got " + interval);
        }
        log.info("rematch sweeper: every {}, PENDING requests after {}, at most {} per pass", interval,
                props.rematch().pendingAge(), props.rematch().batchSize());
        return registrar -> registrar.addFixedDelayTask(new FixedDelayTask(() -> runSafely(sweeper), interval, interval));
    }

    /** One pass; a failure is logged and the next pass simply tries again. */
    static void runSafely(RematchSweeper sweeper) {
        try {
            sweeper.sweep();
        } catch (DependencyUnavailableException e) {
            log.warn("rematch pass skipped, storage unavailable: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("rematch pass failed", e);
        }
    }
}
