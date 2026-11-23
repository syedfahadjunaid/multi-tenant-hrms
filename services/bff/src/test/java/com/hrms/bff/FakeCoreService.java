package com.hrms.bff;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for core-service: records what the BFF forwarded and answers 200. */
final class FakeCoreService implements AutoCloseable {

    record Received(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? null : String.join(", ", values);
        }
    }

    private final HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();

    private FakeCoreService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(exchange.getRequestHeaders());
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(exchange.getRequestMethod(), path, headers,
                    new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
            FakeKeycloak.send(exchange, 200, "{\"path\":\"" + path + "\"}");
        });
        server.start();
    }

    static FakeCoreService start() {
        try {
            return new FakeCoreService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
