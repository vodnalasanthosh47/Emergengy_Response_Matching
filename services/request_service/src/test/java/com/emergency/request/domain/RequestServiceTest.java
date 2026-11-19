package com.emergency.request.domain;

import static com.emergency.common.request.RequestStatus.CANCELLED;
import static com.emergency.common.request.RequestStatus.FULFILLED;
import static com.emergency.common.request.RequestStatus.IN_PROGRESS;
import static com.emergency.common.request.RequestStatus.MATCHED;
import static com.emergency.common.request.RequestStatus.PENDING;
import static com.emergency.common.request.RequestStatus.UNMATCHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emergency.common.model.Category;
import com.emergency.common.model.Location;
import com.emergency.common.request.RequestStatus;
import com.emergency.common.request.RequestView;
import com.emergency.common.request.Requester;
import com.emergency.request.support.InMemoryRequests;

import tools.jackson.databind.json.JsonMapper;

class RequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:15:00.123456Z");

    private final JsonMapper json = JsonMapper.builder().build();
    private InMemoryRequests.Repository repo;
    private InMemoryRequests.Publisher events;
    private RequestService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRequests.Repository();
        events = new InMemoryRequests.Publisher();
        service = new RequestService(repo, events, new DetailsValidator(), Clock.fixed(NOW, ZoneOffset.UTC), 6);
    }

    private NewRequest water() {
        return new NewRequest(Category.WATER, new Location(12.9716, 77.5946), json.readTree("{\"quantity\":20}"),
                new Requester("  Ravi ", " "));
    }

    @Test
    void createStoresAPendingRequestAndPublishesIt() {
        RequestView created = service.create(water());

        assertThat(created.requestId()).matches("[0-9a-f-]{36}");
        assertThat(created.status()).isEqualTo(PENDING);
        assertThat(created.geoCell()).isEqualTo("tdr1v9"); // computed by the server
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.assignedResourceId()).isNull();
        assertThat(created.createdAt()).isEqualTo(Instant.parse("2026-11-19T10:15:00.123Z")); // millisecond precision
        assertThat(created.details()).isEqualTo(json.readTree("{\"quantity\":20,\"unit\":\"L\"}"));
        assertThat(created.requester()).isEqualTo(new Requester("Ravi", null));
        assertThat(repo.records).containsKey(created.requestId());
        assertThat(events.published).containsExactly(created); // the event carries the full stored request
    }

    @Test
    void everyRequestGetsItsOwnId() {
        assertThat(service.create(water()).requestId()).isNotEqualTo(service.create(water()).requestId());
    }

    @Test
    void failedPublishRemovesTheStoredRequestAndReports503() {
        events.down = true;
        assertThatThrownBy(() -> service.create(water())).isInstanceOf(DependencyUnavailableException.class);
        assertThat(repo.records).as("no orphan PENDING request without an event").isEmpty();
        assertThat(repo.deletes).isEqualTo(1);
    }

    @Test
    void storageDownIs503AndNothingIsPublished() {
        repo.down = true;
        assertThatThrownBy(() -> service.create(water())).isInstanceOf(DependencyUnavailableException.class);
        assertThat(events.published).isEmpty();
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> service.create(new NewRequest(Category.WATER, new Location(91.0, 0.0),
                json.readTree("{\"quantity\":1}"), null))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("location");
        assertThatThrownBy(() -> service.create(new NewRequest(Category.WATER, new Location(1.0, 1.0),
                json.readTree("{\"quantity\":0}"), null))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.create(new NewRequest(null, new Location(1.0, 1.0),
                json.readTree("{}"), null))).isInstanceOf(InvalidRequestException.class);
        assertThat(repo.records).isEmpty();
        assertThat(events.published).isEmpty();
    }

    @Test
    void getReturnsTheRequestOr404() {
        RequestView created = service.create(water());
        assertThat(service.get(created.requestId())).isEqualTo(created);
        assertThat(service.get(created.requestId().toUpperCase())).isEqualTo(created); // UUIDs are case-insensitive
        assertThatThrownBy(() -> service.get("00000000-0000-4000-8000-000000000000")).isInstanceOf(RequestNotFoundException.class);
        assertThatThrownBy(() -> service.get("not-a-uuid")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.get("request:x")).isInstanceOf(InvalidRequestException.class);
    }

    /** Every legal client transition, walking the request into the needed starting status first. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PENDING, CANCELLED", "PENDING, PENDING",
            "UNMATCHED, PENDING", "UNMATCHED, CANCELLED",
            "MATCHED, IN_PROGRESS", "MATCHED, CANCELLED",
            "IN_PROGRESS, FULFILLED",
    })
    void legalTransitions(RequestStatus from, RequestStatus to) {
        String id = createIn(from);
        RequestView updated = service.updateStatus(id, to);
        assertThat(updated.status()).isEqualTo(to);
        assertThat(repo.records.get(id).document().status()).isEqualTo(to);
    }

    @ParameterizedTest(name = "{0} -> {1} is 409")
    @CsvSource({
            "PENDING, IN_PROGRESS", "PENDING, FULFILLED",
            "UNMATCHED, IN_PROGRESS", "UNMATCHED, FULFILLED",
            "MATCHED, PENDING", "MATCHED, FULFILLED",
            "IN_PROGRESS, PENDING", "IN_PROGRESS, CANCELLED",
            "FULFILLED, PENDING", "FULFILLED, CANCELLED", "FULFILLED, IN_PROGRESS",
            "CANCELLED, PENDING", "CANCELLED, IN_PROGRESS", "CANCELLED, FULFILLED",
    })
    void illegalTransitionsAreConflicts(RequestStatus from, RequestStatus to) {
        String id = createIn(from);
        long version = repo.records.get(id).version();
        assertThatThrownBy(() -> service.updateStatus(id, to)).isInstanceOf(RequestConflictException.class)
                .hasMessageContaining(from + " to " + to);
        assertThat(repo.records.get(id).version()).as("nothing written").isEqualTo(version);
    }

    @Test
    void matchedAndUnmatchedAreReservedForTheMatchingService() {
        String id = service.create(water()).requestId();
        assertThatThrownBy(() -> service.updateStatus(id, MATCHED)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.updateStatus(id, UNMATCHED)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void settingTheCurrentStatusAgainIsANoOp() {
        String id = service.create(water()).requestId();
        service.updateStatus(id, CANCELLED);
        int writes = repo.updates;
        assertThat(service.updateStatus(id, CANCELLED).status()).isEqualTo(CANCELLED);
        assertThat(repo.updates).isEqualTo(writes);
    }

    /** The Matching Service sets MATCHED between our read and our write: we re-read and decide on the new state. */
    @Test
    void concurrentChangeIsRereadAndDecidedAgain() {
        String id = service.create(water()).requestId();
        repo.beforeUpdate = requestId -> {
            repo.beforeUpdate = x -> { };
            repo.concurrentWrite(requestId, repo.records.get(requestId).document().withStatus(MATCHED, NOW));
        };
        // CANCELLED is still legal from MATCHED, so the retry succeeds on the new version.
        RequestView result = service.updateStatus(id, CANCELLED);
        assertThat(result.status()).isEqualTo(CANCELLED);
        assertThat(result.version()).isEqualTo(3);
    }

    @Test
    void concurrentChangeThatMakesTheTransitionIllegalIsAConflict() {
        String id = createIn(UNMATCHED);
        repo.beforeUpdate = requestId -> {
            repo.beforeUpdate = x -> { };
            repo.concurrentWrite(requestId, repo.records.get(requestId).document().withStatus(CANCELLED, NOW));
        };
        // UNMATCHED -> PENDING (retry) was legal when we read it, but someone cancelled first:
        // the re-read sees CANCELLED, from which PENDING is not reachable.
        assertThatThrownBy(() -> service.updateStatus(id, PENDING)).isInstanceOf(RequestConflictException.class)
                .hasMessageContaining("CANCELLED to PENDING");
        assertThat(repo.records.get(id).document().status()).isEqualTo(CANCELLED);
    }

    @Test
    void endlessConcurrentChangesGiveUpWithAConflict() {
        String id = service.create(water()).requestId();
        repo.beforeUpdate = requestId -> repo.concurrentWrite(requestId, repo.records.get(requestId).document());
        assertThatThrownBy(() -> service.updateStatus(id, CANCELLED)).isInstanceOf(RequestConflictException.class)
                .hasMessageContaining("concurrently");
    }

    @Test
    void statusOfAMissingRequestIs404() {
        assertThatThrownBy(() -> service.updateStatus("00000000-0000-4000-8000-000000000000", CANCELLED))
                .isInstanceOf(RequestNotFoundException.class);
    }

    /** Creates a request and moves it to {@code status} directly in the fake repository. */
    private String createIn(RequestStatus status) {
        String id = service.create(water()).requestId();
        if (status != PENDING) {
            repo.concurrentWrite(id, repo.records.get(id).document().withStatus(status, NOW));
        }
        return id;
    }
}
