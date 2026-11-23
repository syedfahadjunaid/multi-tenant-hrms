package com.hrms.core.config;

import com.hrms.core.security.KeycloakRealmRoleConverter;
import com.hrms.core.security.RateLimitFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * All HTTP security rules live in this one class, so they are easy to review.
 *
 * <p>Model: this service is an OAuth2 <em>resource server</em>. It never sees passwords.
 * Keycloak authenticates the user and issues a signed JWT; every request must carry it as
 * {@code Authorization: Bearer <token>}, and we verify signature, issuer, audience,
 * expiry and tenant before the request reaches a controller.
 */
@Configuration
@EnableMethodSecurity // enables @PreAuthorize on controller/service methods
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, StringRedisTemplate redis, HrmsProperties props)
            throws Exception {
        http
                // Stateless bearer tokens are not sent automatically by browsers, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator runs on the internal management port (8081), see application.yml
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
                // Runs after the JWT is verified, so limits are per user, not per IP.
                .addFilterAfter(new RateLimitFilter(redis, props.rateLimit()), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Verifies every incoming token. Each rule here is a security control you can explain
     * in an interview: signature (JWKS), expiry/not-before, issuer, audience, tenant claim.
     */
    @Bean
    JwtDecoder jwtDecoder(HrmsProperties props) {
        HrmsProperties.Security security = props.security();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri()).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                // signature is checked by the decoder; this adds exp/nbf (with clock skew) and iss
                JwtValidators.createDefaultWithIssuer(security.issuerUri()),
                // the token must have been minted for THIS API, not some other app in the realm
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(security.audience())),
                // no tenant -> no access. Fail closed instead of guessing.
                new JwtClaimValidator<String>("tenant_id", tenant -> tenant != null && !tenant.isBlank()));

        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** Turns Keycloak realm roles into Spring authorities, e.g. HR_ADMIN -> ROLE_HR_ADMIN. */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /** Only the web app's origin may call this API from a browser. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(HrmsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
