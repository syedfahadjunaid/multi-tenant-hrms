package com.hrms.bff.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe view of the {@code hrms.bff.*} section in application.yml. The app refuses to
 * start if a value is missing (for example the client secret), instead of failing at the
 * first sign-in.
 *
 * @param publicUrl where users reach the app (nginx, or Vite in development). Redirect URIs
 *     are built from it rather than from request headers, which a client could forge.
 * @param coreServiceUrl the core API that {@code /api/**} is forwarded to
 */
@Validated
@ConfigurationProperties(prefix = "hrms.bff")
public record BffProperties(
        @NotBlank String publicUrl,
        @NotBlank String coreServiceUrl,
        @Valid @NotNull Keycloak keycloak) {

    /**
     * The BFF's client registration in Keycloak.
     *
     * @param issuerUri the realm as the browser sees it; also the {@code iss} in every token
     * @param backchannelUri the same realm as this service reaches it (inside Docker that is
     *     {@code http://keycloak:8080/...}, because "localhost" there is the container itself)
     */
    public record Keycloak(
            @NotBlank String issuerUri,
            @NotBlank String backchannelUri,
            @NotBlank String clientId,
            @NotBlank String clientSecret) {}
}
