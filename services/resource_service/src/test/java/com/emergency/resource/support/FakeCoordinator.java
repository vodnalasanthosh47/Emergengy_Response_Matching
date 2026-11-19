package com.emergency.resource.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-process stand-in for the coordinator's data API (/v1/data, /v1/query, /readyz)
 * with the same expectedVersion rules, so the whole application can be tested without
 * the storage cluster. The e2e script runs against the real one.
 */
public class FakeCoordinator implements AutoCloseable {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, ObjectNode> records = new TreeMap<>();
    private final HttpServer server;
    public volatile boolean down;

    public FakeCoordinator() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readyz", ex -> reply(ex, down ? 503 : 200, "{}"));
        server.createContext("/v1/data/", this::data);
        server.createContext("/v1/query", this::query);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized ObjectNode record(String key) {
        return records.get(key);
    }

    public synchronized int size() {
        return records.size();
    }

    private synchronized void data(HttpExchange ex) throws IOException {
        if (down) {
            reply(ex, 503, "{\"error\":\"STORAGE_UNAVAILABLE\",\"message\":\"down\",\"timestamp\":\"x\"}");
            return;
        }
        String key = ex.getRequestURI().getRawPath().substring("/v1/data/".length());
        ObjectNode current = records.get(key);
        switch (ex.getRequestMethod()) {
            case "GET" -> reply(ex, current == null ? 404 : 200, current == null ? "{}" : current.toString());
            case "PUT" -> {
                JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
                long ev = body.get("expectedVersion").longValue();
                long version = current == null ? 0 : current.get("version").longValue();
                if (ev == 0 && current != null || ev > 0 && current != null && ev != version) {
                    reply(ex, 409, "{}");
                    return;
                }
                if (ev > 0 && current == null) {
                    reply(ex, 412, "{}");
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
            default -> reply(ex, 405, "{}");
        }
    }

    private synchronized void query(HttpExchange ex) throws IOException {
        if (down) {
            reply(ex, 503, "{}");
            return;
        }
        JsonNode q = json.readTree(ex.getRequestBody().readAllBytes());
        ArrayNode out = json.createArrayNode();
        for (ObjectNode rec : records.values()) {
            JsonNode indexes = rec.get("indexes");
            boolean matches = indexes.get("collection").stringValue().equals(q.get("collection").stringValue());
            for (String name : q.get("match").propertyNames()) {
                matches &= q.get("match").get(name).equals(indexes.get(name));
            }
            if (matches && out.size() < q.get("limit").intValue()) {
                out.add(rec);
            }
        }
        reply(ex, 200, json.createObjectNode().set("records", out).toString());
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
