package com.emergency.request.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-process stand-in for the Go coordinator's data API (/v1/data, /readyz), with the
 * same expectedVersion rules (contracts/openapi/coordinator-api.yaml). Lets the whole
 * Spring application be tested without the storage cluster; the e2e scripts then run
 * against the real one.
 */
public class FakeCoordinator implements AutoCloseable {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, ObjectNode> records = new ConcurrentHashMap<>();
    private final HttpServer server;
    public volatile boolean down;

    public FakeCoordinator() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readyz", ex -> reply(ex, down ? 503 : 200, "{}"));
        server.createContext("/v1/data/", this::data);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public ObjectNode record(String key) {
        return records.get(key);
    }

    public int size() {
        return records.size();
    }

    private synchronized void data(HttpExchange ex) throws IOException {
        if (down) {
            reply(ex, 503, "{\"error\":\"STORAGE_UNAVAILABLE\",\"message\":\"down\",\"timestamp\":\"x\"}");
            return;
        }
        String key = ex.getRequestURI().getRawPath().substring("/v1/data/".length());
        if (!key.matches("^[A-Za-z0-9:_-]{1,256}$")) {
            reply(ex, 400, "{\"error\":\"INVALID_REQUEST\",\"message\":\"bad key " + key + "\",\"timestamp\":\"x\"}");
            return;
        }
        ObjectNode current = records.get(key);
        switch (ex.getRequestMethod()) {
            case "GET" -> reply(ex, current == null ? 404 : 200, current == null ? "{}" : current.toString());
            case "PUT" -> {
                JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
                JsonNode ev = body.get("expectedVersion");
                long version = current == null ? 0 : current.get("version").longValue();
                if (ev != null && ev.longValue() == 0 && current != null) {
                    reply(ex, 409, "{}");
                    return;
                }
                if (ev != null && ev.longValue() > 0 && current == null) {
                    reply(ex, 412, "{}");
                    return;
                }
                if (ev != null && ev.longValue() > 0 && ev.longValue() != version) {
                    reply(ex, 409, "{}");
                    return;
                }
                ObjectNode rec = json.createObjectNode();
                rec.put("key", key);
                rec.set("value", body.get("value"));
                rec.set("indexes", body.get("indexes"));
                rec.put("version", version + 1);
                rec.put("updatedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
                records.put(key, rec);
                reply(ex, current == null ? 201 : 200, rec.toString());
            }
            case "DELETE" -> {
                String q = ex.getRequestURI().getQuery();
                if (current == null) {
                    reply(ex, 404, "{}");
                } else if (q != null && !q.equals("expectedVersion=" + current.get("version").longValue())) {
                    reply(ex, 409, "{}");
                } else {
                    records.remove(key);
                    reply(ex, 204, null);
                }
            }
            default -> reply(ex, 405, "{}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body == null ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
