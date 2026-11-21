package com.emergency.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.common.storage.StorageKeys;
import com.emergency.matching.support.FakeCoordinator;
import com.emergency.matching.support.Fixtures;

/**
 * The timer itself: with a short REMATCH_INTERVAL, a request waiting for a resource is
 * matched on its own once the resource appears, without POST /match or a new event.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"emergency.rematch.interval=300ms", "emergency.rematch.pending-age=0s"})
class RematchScheduleTest {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static final FakeCoordinator COORDINATOR;

    static {
        try {
            COORDINATOR = new FakeCoordinator();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void coordinator(DynamicPropertyRegistry registry) {
        registry.add("emergency.coordinator-url", COORDINATOR::url);
    }

    @AfterAll
    static void stopCoordinator() {
        COORDINATOR.close();
    }

    private String status(RequestDocument r) {
        return COORDINATOR.record(StorageKeys.request(r.requestId())).path("value").path("status").stringValue();
    }

    private RequestDocument store(RequestStatus status) {
        RequestDocument r = Fixtures.request(UUID.randomUUID().toString(), Category.SHELTER, Fixtures.LAT, Fixtures.LNG,
                "{\"people\":3}", status);
        COORDINATOR.put(StorageKeys.request(r.requestId()), r, Map.of(
                "collection", "request", "type", "SHELTER", "status", status.name(), "geoCell", r.geoCell()));
        return r;
    }

    @Test
    void waitingRequestsAreMatchedOnTheirOwnOnceAResourceAppears() throws Exception {
        RequestDocument unmatched = store(RequestStatus.UNMATCHED);
        RequestDocument lostEvent = store(RequestStatus.PENDING); // its RequestCreated never arrived
        Thread.sleep(1000); // a few passes with nothing to match: both stay waiting
        assertThat(status(unmatched)).isEqualTo("UNMATCHED");
        assertThat(status(lostEvent)).isEqualTo("UNMATCHED"); // tried by the sweeper, nothing nearby

        ResourceDocument shelter = Fixtures.resource(Category.SHELTER, Fixtures.LAT + 0.001, Fixtures.LNG, "10", "10",
                ResourceStatus.AVAILABLE, null);
        COORDINATOR.put(StorageKeys.resourceIdToKey(shelter.resourceId()), shelter, Map.of(
                "collection", "resource", "type", "SHELTER", "status", "AVAILABLE", "geoCell", shelter.geoCell()));

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!("MATCHED".equals(status(unmatched)) && "MATCHED".equals(status(lostEvent)))) {
            assertThat(System.nanoTime()).as("timed out waiting for the sweeper to match both").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(COORDINATOR.record(StorageKeys.resourceIdToKey(shelter.resourceId()))
                .path("value").path("capacity").path("remaining").intValue()).as("each claimed once").isEqualTo(4);
    }
}
