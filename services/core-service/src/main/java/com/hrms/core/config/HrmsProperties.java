package com.hrms.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe view of the {@code hrms.*} section in application.yml.
 *
 * <p>Java records are immutable data carriers: the compiler generates the constructor,
 * accessors ({@code security()}, {@code ai()}, ...), equals/hashCode and toString.
 * {@code @Validated} makes the app refuse to start if a required value is missing,
 * which is much better than failing on the first request in production.
 */
@Validated
@ConfigurationProperties(prefix = "hrms")
public record HrmsProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Ai ai,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Leave leave) {

    /** Leave policy. One allowance for everyone keeps the demo simple. */
    public record Leave(@Positive int annualAllowanceDays) {}

    /** Where to get JWT signing keys and what the token must say about itself. */
    public record Security(
            @NotBlank String issuerUri,
            @NotBlank String jwkSetUri,
            @NotBlank String audience) {}

    /** The Python AI service. */
    public record Ai(
            @NotBlank String baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {}

    /** Per-user request budgets, enforced with Redis. */
    public record RateLimit(
            @Positive int requestsPerMinute,
            @Positive int assistantRequestsPerMinute) {}

    public record Cors(@NotEmpty List<String> allowedOrigins) {}
}
