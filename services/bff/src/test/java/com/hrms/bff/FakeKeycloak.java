package com.hrms.bff;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Just enough of Keycloak for the BFF's server-to-server calls: the token endpoint and the
 * signing keys. (The browser-facing login page is never called: tests play the browser and
 * jump straight to the callback with a code.) Built on the JDK's own HTTP server.
 */
final class FakeKeycloak implements AutoCloseable {

    static final String CLIENT_ID = "hrms-bff";
    static final String CLIENT_SECRET = "test-secret";
    static final String CODE = "test-code";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final AtomicInteger issued = new AtomicInteger();

    /** Every token request the BFF made, as form fields plus its Authorization header. */
    final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

    volatile String nonce; // what the BFF sent with the authorization request
    volatile int expiresInSeconds;
    volatile boolean rejectRefresh;

    private FakeKeycloak() throws IOException, JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/realms/hrms/protocol/openid-connect/token", this::token);
        server.createContext("/realms/hrms/protocol/openid-connect/certs",
                exchange -> send(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString()));
        server.start();
        reset();
    }

    static FakeKeycloak start() {
        try {
            return new FakeKeycloak();
        } catch (IOException | JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    void reset() {
        issued.set(0);
        tokenRequests.clear();
        nonce = null;
        expiresInSeconds = 300;
        rejectRefresh = false;
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/hrms";
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
        form.put("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
        tokenRequests.add(form);

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(UTF_8));
        if (!expectedAuth.equals(form.get("Authorization"))) {
            send(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        switch (form.getOrDefault("grant_type", "")) {
            case "authorization_code" -> {
                if (!CODE.equals(form.get("code")) || form.get("code_verifier") == null) {
                    send(exchange, 400, "{\"error\":\"invalid_grant\"}");
                } else {
                    send(exchange, 200, tokens(true));
                }
            }
            case "refresh_token" -> {
                if (rejectRefresh) {
                    send(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}");
                } else {
                    send(exchange, 200, tokens(false));
                }
            }
            default -> send(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private String tokens(boolean withIdToken) {
        int n = issued.incrementAndGet();
        String idToken = withIdToken ? ",\"id_token\":\"" + idToken() + "\"" : "";
        return """
                {"access_token":"access-%d","token_type":"Bearer","expires_in":%d,\
                "refresh_token":"refresh-%d","scope":"openid profile email"%s}"""
                .formatted(n, expiresInSeconds, n, idToken);
    }

    private String idToken() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("bob-id")
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("preferred_username", "bob")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            fields.put(key, value);
        }
        return fields;
    }

    static void send(HttpExchange exchange, int status, String json) {
        try (exchange) {
            byte[] body = json.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
