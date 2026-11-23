package com.hrms.core;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.hrms.core.security.KeycloakRealmRoleConverter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Test helpers for "signed-in" requests without running Keycloak. */
public final class TestUsers {

    private TestUsers() {}

    /** A fresh tenant id, so tests never see each other's data. */
    public static String newTenant() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A verified JWT for a user in {@code tenant} with the given Keycloak realm roles. */
    public static RequestPostProcessor user(String tenant, String... roles) {
        return jwt()
                .jwt(token -> token
                        .subject("user-" + tenant)
                        .claim("preferred_username", "user-" + tenant)
                        .claim("tenant_id", tenant)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /** A specific person, identified by a verified email (that's how accounts link to employee records). */
    public static RequestPostProcessor person(String tenant, String email, String... roles) {
        return account(tenant, email, true, roles);
    }

    /** Same, but Keycloak hasn't verified the email, so it must not be trusted. */
    public static RequestPostProcessor unverified(String tenant, String email, String... roles) {
        return account(tenant, email, false, roles);
    }

    /** Runs code outside an HTTP request as if a user of {@code tenant} were signed in (e.g. to read tenant-filtered tables). */
    public static <T> T runAsTenant(String tenant, Supplier<T> work) {
        Jwt token = Jwt.withTokenValue("test").header("alg", "none")
                .subject("test").claim("tenant_id", tenant).build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(token));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static RequestPostProcessor account(String tenant, String email, boolean verified, String... roles) {
        return jwt()
                .jwt(token -> token
                        .subject("sub-" + email)
                        .claim("preferred_username", email.substring(0, email.indexOf('@')))
                        .claim("email", email)
                        .claim("email_verified", verified)
                        .claim("tenant_id", tenant)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
