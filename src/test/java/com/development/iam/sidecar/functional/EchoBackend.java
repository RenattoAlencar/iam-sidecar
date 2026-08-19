package com.development.iam.sidecar.functional;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class EchoBackend implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger receivedRequests = new AtomicInteger();

    private EchoBackend(HttpServer server) {
        this.server = server;
    }

    public static EchoBackend start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        EchoBackend backend = new EchoBackend(server);
        server.createContext("/", backend::handle);

        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "echo-backend");
            thread.setDaemon(true);
            return thread;
        }));

        server.start();
        return backend;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public int receivedRequests() {
        return receivedRequests.get();
    }

    public void resetCounter() {
        receivedRequests.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedRequests.incrementAndGet();

        String path = exchange.getRequestURI().getRawPath();
        byte[] body;

        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }

        if (path.startsWith("/__slow")) {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int status = path.startsWith("/__status/")
                ? Integer.parseInt(path.substring("/__status/".length()))
                : 200;

        byte[] response = echoJson(exchange, body).getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Backend-Marker", "echo");
        exchange.sendResponseHeaders(status, response.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private static String echoJson(HttpExchange exchange, byte[] body) {
        StringBuilder json = new StringBuilder("{");

        json.append("\"method\":\"").append(exchange.getRequestMethod()).append("\",");
        json.append("\"path\":\"").append(escape(exchange.getRequestURI().getRawPath())).append("\",");

        String query = exchange.getRequestURI().getRawQuery();
        json.append("\"query\":").append(query == null ? "null" : "\"" + escape(query) + "\"").append(',');

        json.append("\"bodyLength\":").append(body.length).append(',');
        json.append("\"body\":\"").append(escape(new String(body, StandardCharsets.UTF_8))).append("\",");

        json.append("\"headers\":{");
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach(headers::put);

        List<String> entries = new ArrayList<>();
        headers.forEach((name, values) -> {
            List<String> quoted = values.stream().map(value -> "\"" + escape(value) + "\"").toList();
            entries.add("\"" + escape(name.toLowerCase()) + "\":[" + String.join(",", quoted) + "]");
        });
        json.append(String.join(",", entries)).append("}}");

        return json.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}