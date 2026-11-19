package com.emergency.common.storage;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Java services' only way to reach storage: an HTTP client for the coordinator data
 * API (contracts/openapi/coordinator-api.yaml). Services never talk to storage nodes or
 * MongoDB.
 *
 * <p>Every non-success answer becomes one exception type, so services handle storage
 * outcomes, not HTTP codes:
 *
 * <table>
 * <tr><th>coordinator</th><th>exception</th><th>what the caller does</th></tr>
 * <tr><td>409</td><td>{@link VersionConflictException}</td><td>re-read, decide again</td></tr>
 * <tr><td>412</td><td>{@link PreconditionFailedException}</td><td>the record is gone</td></tr>
 * <tr><td>404 (delete)</td><td>{@link RecordNotFoundException}</td><td>the record is gone</td></tr>
 * <tr><td>400</td><td>{@link InvalidStorageRequestException}</td><td>bug: fix the service</td></tr>
 * <tr><td>5xx, unreachable, timeout</td><td>{@link StorageUnavailableException}</td><td>answer 503</td></tr>
 * </table>
 *
 * <p>Writes are always conditional ({@link #create} = "must not exist", {@link #replace} =
 * "must still be at this version"). There is deliberately no unconditional write: every
 * claim and status change must use optimistic concurrency (spec §2.2-4).
 */
public class StorageClient {

    /** The coordinator's key pattern. Checked here so a key can never inject anything into the URL. */
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9:_-]{1,256}$");

    private final RestClient http;
    private final JsonMapper json;
    /**
     * Reads stored data tolerantly: a field this service does not know (added by another
     * service, or a newer version of this one) is ignored instead of failing the read.
     * The services' own API input stays strict; this only applies to data from storage.
     */
    private final JsonMapper reader;

    /**
     * @param coordinatorUrl e.g. {@code http://coordinator:8080}
     * @param timeout        bound on connecting and on each call. A coordinator write can
     *                       take a few seconds during a failover, so keep this above the
     *                       coordinator's own STORAGE_TIMEOUT (3 s) × 2.
     */
    public StorageClient(String coordinatorUrl, Duration timeout, JsonMapper json) {
        this(RestClient.builder().baseUrl(coordinatorUrl).requestFactory(requestFactory(timeout)), json);
    }

    /** For tests: a pre-configured builder (e.g. bound to a mock server). */
    public StorageClient(RestClient.Builder builder, JsonMapper json) {
        this.http = builder.build();
        this.json = json;
        this.reader = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    /** Reads a record; empty if there is none (or it was deleted). */
    public Optional<StorageRecord> get(String key) {
        Response r = send(HttpMethod.GET, dataPath(key), null);
        if (r.status() == 404) {
            return Optional.empty();
        }
        if (r.status() != 200) {
            throw errorFor(r, key);
        }
        return Optional.of(parseRecord(r.body()));
    }

    /** Creates a record that must not exist yet ({@code expectedVersion 0}). Version 1. */
    public StorageRecord create(String key, Object value, Map<String, String> indexes) {
        return put(key, value, indexes, 0);
    }

    /**
     * Replaces a record only if it is still at {@code expectedVersion} (compare-and-set).
     * Throws {@link VersionConflictException} if someone changed it first.
     */
    public StorageRecord replace(String key, Object value, Map<String, String> indexes, long expectedVersion) {
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be >= 1 (use create for new records)");
        }
        return put(key, value, indexes, expectedVersion);
    }

    /** Deletes a record only if it is still at {@code expectedVersion}. */
    public void delete(String key, long expectedVersion) {
        Response r = send(HttpMethod.DELETE, dataPath(key) + "?expectedVersion=" + expectedVersion, null);
        if (r.status() != 204) {
            throw errorFor(r, key);
        }
    }

    /** Finds records by index attributes (and cells), sorted by key. */
    public List<StorageRecord> query(StorageQuery query) {
        Response r = send(HttpMethod.POST, "/v1/query", json.writeValueAsString(query));
        if (r.status() != 200) {
            throw errorFor(r, "query");
        }
        try {
            List<StorageRecord> records = new ArrayList<>();
            for (JsonNode node : json.readTree(r.body()).path("records")) {
                records.add(reader.treeToValue(node, StorageRecord.class));
            }
            return records;
        } catch (JacksonException e) {
            throw new StorageUnavailableException("invalid query response from the coordinator", e);
        }
    }

    /** Whether the coordinator reports every shard ready ({@code GET /readyz}). */
    public boolean isReady() {
        try {
            return send(HttpMethod.GET, "/readyz", null).status() == 200;
        } catch (StorageUnavailableException e) {
            return false;
        }
    }

    /** Converts a record's value into a domain type. */
    public <T> T valueAs(StorageRecord record, Class<T> type) {
        return reader.treeToValue(record.value(), type);
    }

    private StorageRecord put(String key, Object value, Map<String, String> indexes, long expectedVersion) {
        ObjectNode body = json.createObjectNode();
        body.set("value", json.valueToTree(value));
        body.set("indexes", json.valueToTree(indexes));
        body.put("expectedVersion", expectedVersion);
        Response r = send(HttpMethod.PUT, dataPath(key), json.writeValueAsString(body));
        if (r.status() != 200 && r.status() != 201) {
            throw errorFor(r, key);
        }
        return parseRecord(r.body());
    }

    private record Response(int status, String body) {
    }

    /**
     * {@code /v1/data/{key}} with the key as-is. Not a URI template variable: those are
     * percent-encoded, which would send "request%3A..." instead of "request:...". The key's
     * characters are all legal in a URL path, which the pattern check guarantees.
     */
    private static String dataPath(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid storage key: " + key);
        }
        return "/v1/data/" + key;
    }

    private Response send(HttpMethod method, String uri, String body) {
        try {
            RestClient.RequestBodySpec request = http.method(method).uri(uri);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, res) -> new Response(res.getStatusCode().value(), read(res.getBody())));
        } catch (RestClientException e) {
            // Connection refused, DNS failure, timeout: the coordinator is unreachable.
            throw new StorageUnavailableException("storage coordinator unreachable: " + e.getMessage(), e);
        }
    }

    private static String read(java.io.InputStream body) throws IOException {
        return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private StorageRecord parseRecord(String body) {
        try {
            return reader.readValue(body, StorageRecord.class);
        } catch (JacksonException e) {
            throw new StorageUnavailableException("invalid record from the coordinator", e);
        }
    }

    private StorageException errorFor(Response r, String key) {
        return switch (r.status()) {
            case 409 -> new VersionConflictException(key);
            case 412 -> new PreconditionFailedException(key);
            case 404 -> new RecordNotFoundException(key);
            case 400 -> new InvalidStorageRequestException("coordinator rejected " + key + ": " + message(r.body()));
            default -> new StorageUnavailableException("coordinator answered " + r.status() + " for " + key + ": " + message(r.body()));
        };
    }

    /** The {@code message} of the coordinator's error body, or the raw body. */
    private String message(String body) {
        try {
            return json.readTree(body).path("message").asString(body);
        } catch (JacksonException e) {
            return body;
        }
    }
}
