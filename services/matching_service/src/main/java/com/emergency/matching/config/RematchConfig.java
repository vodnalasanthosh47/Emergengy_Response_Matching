package com.emergency.matching.config;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;

import com.emergency.common.storage.StorageClient;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.RematchPublisher;
import com.emergency.matching.domain.RematchSweeper;
import com.emergency.matching.domain.SweepLease;
import com.emergency.matching.domain.WaitingRequests;
import com.emergency.matching.messaging.RabbitRematchPublisher;
import com.emergency.matching.storage.StorageSweepLease;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the {@link RematchSweeper} every {@code REMATCH_INTERVAL} (default 2 min).
 *
 * <p>Fixed <em>delay</em>, not fixed rate: the next pass starts one interval after the
 * previous one ended, so passes never overlap. The first pass runs one interval after
 * startup. {@code REMATCH_ENABLED=false} switches the sweeper off entirely.
 *
 * <p>Every pod runs the timer, but only the holder of the storage lease sweeps. The
 * lease lasts 1.5 intervals: the holder renews it on each pass and keeps it; if it dies,
 * another pod takes over within about 2.5 intervals.
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

    /** Unique per process: the pod (or container) name plus a random suffix, so a restarted pod is a new holder. */
    @Bean
    SweepLease sweepLease(StorageClient storage, Clock clock) {
        String host = System.getenv().getOrDefault("HOSTNAME", "matching-service");
        return new StorageSweepLease(storage, clock, host + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Bean
    RematchSweeper rematchSweeper(WaitingRequests waiting, RematchPublisher publisher, SweepLease lease, Clock clock,
            MatchingServiceProperties props) {
        Duration interval = props.rematch().interval();
        return new RematchSweeper(waiting, publisher, lease, interval.plus(interval.dividedBy(2)), clock,
                props.rematch().pendingAge(), props.rematch().batchSize());
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
