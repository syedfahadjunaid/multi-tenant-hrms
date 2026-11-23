package com.hrms.bff;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole BFF flow over real HTTP: the test plays the browser (a cookie-keeping HTTP
 * client that doesn't follow redirects), a fake Keycloak issues tokens, and a fake core API
 * records what the BFF forwards. Sessions are stored in a real Redis (Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class BffFlowTests {

    static final FakeKeycloak keycloak = FakeKeycloak.start();
    static final FakeCoreService core = FakeCoreService.start();

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("hrms.bff.keycloak.issuer-uri", keycloak::issuer);
        registry.add("hrms.bff.keycloak.backchannel-uri", keycloak::issuer);
        registry.add("hrms.bff.keycloak.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
        registry.add("hrms.bff.core-service-url", core::url);
        registry.add("hrms.bff.public-url", () -> "http://localhost:5280");
    }

    @AfterAll
    static void stopFakes() {
        keycloak.close();
        core.close();
    }

    @LocalServerPort
    int port;

    CookieManager cookies;
    HttpClient browser;

    @BeforeEach
    void newBrowser() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        browser = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        keycloak.reset();
        core.received.clear();
    }

    @Test
    void apiCallsWithoutASessionGet401AndNeverReachTheApi() {
        assertThat(get("/api/me").statusCode()).isEqualTo(401);
        assertThat(core.received).isEmpty();
    }

    @Test
    void signInSendsTheBrowserToKeycloakWithPkceAndAFixedRedirectUri() {
        HttpResponse<String> start = get("/oauth2/authorization/keycloak");

        assertThat(start.statusCode()).isEqualTo(302);
        URI location = URI.create(start.headers().firstValue("Location").orElseThrow());
        assertThat(location.toString()).startsWith(keycloak.issuer() + "/protocol/openid-connect/auth?");
        assertThat(query(location))
                .containsEntry("client_id", "hrms-bff")
                .containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://localhost:5280/login/oauth2/code/keycloak")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(start.headers().allValues("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("HRMS_SESSION=")
                .contains("HttpOnly")
                .contains("SameSite=Lax"));
    }

    @Test
    void signedInCallsReachTheApiWithTheAccessTokenAndWithoutTheBrowsersCookies() {
        signIn();

        HttpResponse<String> response = get("/api/me");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/api/me");
        FakeCoreService.Received forwarded = core.received.getFirst();
        assertThat(forwarded.header("Authorization")).isEqualTo("Bearer access-1");
        assertThat(forwarded.header("Cookie")).isNull();
        // The code was exchanged server-to-server, with the client secret and the PKCE verifier.
        assertThat(keycloak.tokenRequests).singleElement().satisfies(request -> assertThat(request)
                .containsEntry("grant_type", "authorization_code")
                .containsKey("code_verifier"));
    }

    @Test
    void stateChangingCallsNeedTheCsrfHeader() {
        signIn();
        get("/api/me"); // any request hands out the CSRF cookie
        core.received.clear();

        assertThat(post("/api/leave/requests", "{}").statusCode()).isEqualTo(403);
        assertThat(core.received).isEmpty();

        HttpResponse<String> allowed = post("/api/leave/requests", "{}", "X-XSRF-TOKEN", cookie("HRMS-XSRF-TOKEN"));
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(core.received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.body()).isEqualTo("{}");
            assertThat(request.header("X-XSRF-TOKEN")).isNull();
        });
    }

    @Test
    void anExpiredAccessTokenIsRefreshedOnTheServer() {
        keycloak.expiresInSeconds = 1; // inside Spring's 60-second clock skew: "expired" at once
        signIn();

        assertThat(get("/api/me").statusCode()).isEqualTo(200);

        assertThat(core.received.getLast().header("Authorization")).isEqualTo("Bearer access-2");
        assertThat(keycloak.tokenRequests)
                .extracting(request -> request.get("grant_type"))
                .containsExactly("authorization_code", "refresh_token");
    }

    @Test
    void whenKeycloakRejectsTheRefreshTokenTheSessionEnds() {
        keycloak.expiresInSeconds = 1;
        keycloak.rejectRefresh = true; // e.g. the Keycloak session timed out or was revoked
        signIn();

        assertThat(get("/api/me").statusCode()).isEqualTo(401);
        assertThat(get("/api/me").statusCode()).isEqualTo(401); // the whole session, not one call
        assertThat(core.received).isEmpty();
    }

    @Test
    void signOutNeedsTheCsrfHeaderAndEndsBothSessions() {
        signIn();
        get("/api/me");
        String csrf = cookie("HRMS-XSRF-TOKEN");

        assertThat(post("/logout", "").statusCode()).isEqualTo(403);

        HttpResponse<String> logout = post("/logout", "", "X-XSRF-TOKEN", csrf);
        assertThat(logout.statusCode()).isEqualTo(200);
        URI keycloakLogout = URI.create(jsonString(logout.body(), "logoutUrl"));
        assertThat(keycloakLogout.toString()).startsWith(keycloak.issuer() + "/protocol/openid-connect/logout?");
        assertThat(query(keycloakLogout))
                .containsEntry("post_logout_redirect_uri", "http://localhost:5280/")
                .containsKey("id_token_hint");

        assertThat(get("/api/me").statusCode()).isEqualTo(401);
    }

    // --- the browser ------------------------------------------------------------------------

    /** What a browser does: start sign-in, (log in at Keycloak), come back with a code. */
    private void signIn() {
        HttpResponse<String> start = get("/oauth2/authorization/keycloak");
        Map<String, String> authorizationRequest =
                query(URI.create(start.headers().firstValue("Location").orElseThrow()));
        keycloak.nonce = authorizationRequest.get("nonce");

        HttpResponse<String> callback = get("/login/oauth2/code/keycloak?code=" + FakeKeycloak.CODE
                + "&state=" + URLEncoder.encode(authorizationRequest.get("state"), UTF_8));

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location")).hasValue("/");
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(url(path)).GET());
    }

    private HttpResponse<String> post(String path, String json, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie"));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), UTF_8), URLDecoder.decode(pair.substring(eq + 1), UTF_8));
        }
        return params;
    }

    private static String jsonString(String json, String field) {
        Matcher match = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(match.find()).as("%s in %s", field, json).isTrue();
        return match.group(1);
    }
}
