package com.emergency.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

class StorageClientTest {

    private static final String KEY = "request:f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d";
    private static final String RECORD = """
            {"key":"%s","value":{"status":"PENDING","n":20},"indexes":{"collection":"request","status":"PENDING"},
             "version":3,"updatedAt":"2026-11-19T10:15:00.123Z"}""".formatted(KEY);
    private static final String ERROR = """
            {"error":"X","message":"from the coordinator","timestamp":"2026-11-19T10:15:00Z"}""";

    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private StorageClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://coordinator:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StorageClient(builder, json);
    }

    @Test
    void getParsesTheRecord() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(RECORD, MediaType.APPLICATION_JSON));
        StorageRecord r = client.get(KEY).orElseThrow();
        assertThat(r.key()).isEqualTo(KEY);
        assertThat(r.version()).isEqualTo(3);
        assertThat(r.updatedAt()).isEqualTo(Instant.parse("2026-11-19T10:15:00.123Z"));
        assertThat(r.indexes()).containsEntry("status", "PENDING");
        assertThat(r.value().get("n").isIntegralNumber()).isTrue(); // integers stay integers
        server.verify();
    }

    /** A field written by some other service (or a newer version) must not break reading. */
    @Test
    void storedDocumentsAreReadTolerantly() {
        JsonMapper strict = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://coordinator:8080");
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        StorageClient c = new StorageClient(builder, strict);
        s.expect(requestTo("http://coordinator:8080/v1/data/" + KEY)).andRespond(withSuccess("""
                {"key":"%s","value":{"name":"x","addedLater":true},"indexes":{},"version":1,
                 "updatedAt":"2026-11-19T10:15:00Z","deleted":false,"newEnvelopeField":1}""".formatted(KEY), MediaType.APPLICATION_JSON));
        StorageRecord r = c.get(KEY).orElseThrow();
        assertThat(c.valueAs(r, Named.class)).isEqualTo(new Named("x"));
    }

    record Named(String name) {
    }

    @Test
    void getOfAMissingRecordIsEmpty() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY)).andRespond(withStatus(HttpStatus.NOT_FOUND).body(ERROR));
        assertThat(client.get(KEY)).isEmpty();
    }

    @Test
    void createSendsExpectedVersionZero() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY)).andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"value":{"status":"PENDING"},"indexes":{"collection":"request"},"expectedVersion":0}""", true))
                .andRespond(withStatus(HttpStatus.CREATED).body(RECORD).contentType(MediaType.APPLICATION_JSON));
        assertThat(client.create(KEY, Map.of("status", "PENDING"), Map.of("collection", "request")).version()).isEqualTo(3);
        server.verify();
    }

    @Test
    void replaceSendsTheExpectedVersion() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY))
                .andExpect(content().json("""
                        {"value":{},"indexes":{"collection":"request"},"expectedVersion":7}""", true))
                .andRespond(withSuccess(RECORD, MediaType.APPLICATION_JSON));
        client.replace(KEY, Map.of(), Map.of("collection", "request"), 7);
        server.verify();
        assertThatThrownBy(() -> client.replace(KEY, Map.of(), Map.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "409, VersionConflictException",
            "412, PreconditionFailedException",
            "400, InvalidStorageRequestException",
            "503, StorageUnavailableException",
            "500, StorageUnavailableException",
            "502, StorageUnavailableException",
    })
    void writeErrorsBecomeOneExceptionEach(int status, String exception) {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body(ERROR).contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.replace(KEY, Map.of(), Map.of("collection", "request"), 1))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo(exception));
    }

    @Test
    void badRequestKeepsTheCoordinatorsMessage() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(ERROR).contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.create(KEY, Map.of(), Map.of()))
                .isInstanceOf(InvalidStorageRequestException.class).hasMessageContaining("from the coordinator");
    }

    @Test
    void deleteIsConditional() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY + "?expectedVersion=4"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        client.delete(KEY, 4);
        server.verify();
    }

    @Test
    void deleteOfAMissingRecord() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY + "?expectedVersion=4"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body(ERROR));
        assertThatThrownBy(() -> client.delete(KEY, 4)).isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    void queryPostsTheBodyAndParsesRecords() {
        server.expect(requestTo("http://coordinator:8080/v1/query")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"collection":"resource","geoCells":["tdr1v9","tdr1v8"],"match":{"type":"WATER"},"limit":10}""", true))
                .andRespond(withSuccess("{\"records\":[" + RECORD + "," + RECORD + "]}", MediaType.APPLICATION_JSON));
        List<StorageRecord> records = client.query(new StorageQuery("resource", List.of("tdr1v9", "tdr1v8"), Map.of("type", "WATER"), 10));
        assertThat(records).hasSize(2);
        server.verify();
    }

    @Test
    void garbageFromTheCoordinatorIsUnavailableNotACrash() {
        server.expect(requestTo("http://coordinator:8080/v1/data/" + KEY)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.get(KEY)).isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void readyReflectsReadyz() {
        server.expect(requestTo("http://coordinator:8080/readyz")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://coordinator:8080/readyz")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(client.isReady()).isTrue();
        assertThat(client.isReady()).isFalse();
    }

    @Test
    void keysOutsideTheContractPatternNeverReachTheUrl() {
        assertThatThrownBy(() -> client.get("request:../../admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get("request:a?b=c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.delete("", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A real HTTP client against a port nobody listens on: unreachable must mean "unavailable". */
    @Test
    void unreachableCoordinatorIsUnavailable() {
        StorageClient real = new StorageClient("http://127.0.0.1:1", Duration.ofMillis(500), json);
        assertThatThrownBy(() -> real.get(KEY)).isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> real.create(KEY, Map.of(), Map.of())).isInstanceOf(StorageUnavailableException.class);
        assertThat(real.isReady()).isFalse();
    }
}
