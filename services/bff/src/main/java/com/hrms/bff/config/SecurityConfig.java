package com.hrms.bff.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * All HTTP security rules for the BFF, in one place.
 *
 * <p>Model: the browser holds an HttpOnly session cookie and nothing else. Because browsers
 * send cookies automatically, every state-changing request must also carry a CSRF token
 * (the double-submit pattern: JavaScript copies the token from a readable cookie into the
 * {@code X-XSRF-TOKEN} header, which another site cannot do).
 */
@Configuration
public class SecurityConfig {

    /** Readable by the SPA. Not "XSRF-TOKEN": cookies are shared by every port on localhost. */
    static final String CSRF_COOKIE = "HRMS-XSRF-TOKEN";

    @Bean
    SecurityFilterChain bffSecurity(
            HttpSecurity http, ClientRegistrationRepository registrations, BffProperties props, JsonMapper json)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Nothing else lives here: the SPA itself is served by nginx.
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        // The SPA starts sign-in by navigating here; no generated login page.
                        .loginPage("/oauth2/authorization/" + KeycloakClientConfig.REGISTRATION_ID)
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?signin=failed"))
                // An API call without a session gets 401 (not a redirect to Keycloak, which
                // fetch() would follow invisibly); the SPA then starts a sign-in itself.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf
                        .spa() // cookie-based token, read by JavaScript and sent back in a header
                        .csrfTokenRepository(csrfCookieRepository()))
                .logout(logout -> logout
                        .logoutUrl("/logout") // POST only, with the CSRF header
                        .logoutSuccessHandler(keycloakLogout(registrations, props, json)));
        return http.build();
    }

    private static CookieCsrfTokenRepository csrfCookieRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        return repository;
    }

    /**
     * Signing out ends the BFF session and then the Keycloak session (OpenID Connect
     * RP-initiated logout), otherwise the next sign-in would succeed without a password.
     *
     * <p>Spring's handler would answer with a redirect, but the SPA calls {@code /logout}
     * with fetch() (it has to, to send the CSRF header), and fetch() can't hand a cross-site
     * redirect to the browser. So the Keycloak logout URL is returned as JSON instead, and the
     * SPA navigates there.
     */
    private static LogoutSuccessHandler keycloakLogout(
            ClientRegistrationRepository registrations, BffProperties props, JsonMapper json) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri(props.publicUrl() + "/");
        handler.setRedirectStrategy((request, response, url) -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of("logoutUrl", url));
        });
        return handler;
    }
}
