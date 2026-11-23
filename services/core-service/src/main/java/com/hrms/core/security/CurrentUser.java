package com.hrms.core.security;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Convenience accessors for "who is calling?".
 *
 * <p>Spring Security stores the verified token in a thread-bound {@code SecurityContext}
 * for the duration of each request, so these static methods work anywhere in the call stack.
 */
public final class CurrentUser {

    public static final String TENANT_CLAIM = "tenant_id";

    private CurrentUser() {}

    public static Optional<JwtAuthenticationToken> authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwtAuth ? Optional.of(jwtAuth) : Optional.empty();
    }

    /** Tenant (company) of the caller, taken only from the signed token, never from the request body. */
    public static Optional<String> tenantId() {
        return authentication()
                .map(auth -> auth.getToken().getClaimAsString(TENANT_CLAIM))
                .filter(tenant -> !tenant.isBlank());
    }

    /** Stable user id ({@code sub} claim). Unlike the username, it never changes. */
    public static String subject() {
        return authentication().map(JwtAuthenticationToken::getName).orElse("anonymous");
    }

    /**
     * The caller's email, but only if Keycloak has verified it. An unverified (for example
     * self-edited) address could belong to someone else, so it must never link an account
     * to an employee record.
     */
    public static Optional<String> verifiedEmail() {
        return authentication()
                .map(JwtAuthenticationToken::getToken)
                .filter(token -> Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")))
                .map(token -> token.getClaimAsString("email"))
                .filter(email -> !email.isBlank())
                .map(email -> email.toLowerCase(Locale.ROOT));
    }

    /** Human-readable name for history entries, e.g. "Mark Manager". */
    public static String displayName() {
        return authentication()
                .map(auth -> auth.getToken().getClaimAsString("name"))
                .filter(name -> !name.isBlank())
                .orElseGet(CurrentUser::username);
    }

    /** First name for greetings ("Welcome, Mark"); falls back to the display name. */
    public static String firstName() {
        return authentication()
                .map(auth -> auth.getToken().getClaimAsString("given_name"))
                .filter(name -> !name.isBlank())
                .orElseGet(CurrentUser::displayName);
    }

    public static boolean hasRole(Role role) {
        return roles().contains(role.name());
    }

    public static String username() {
        return authentication()
                .map(auth -> auth.getToken().getClaimAsString("preferred_username"))
                .orElse("anonymous");
    }

    public static List<String> roles() {
        return authentication().stream()
                .flatMap(auth -> auth.getAuthorities().stream())
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList();
    }

    /** The raw access token, used to call the AI service on the user's behalf (token relay). */
    public static String bearerToken() {
        return authentication()
                .map(auth -> auth.getToken().getTokenValue())
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}
