package com.hrms.core.security;

import com.hrms.core.config.HrmsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter backed by Redis, so the limit holds across every instance of
 * this service. The AI assistant gets a much smaller budget because each call costs money.
 *
 * <p>Redis keys look like {@code rl:ai:<user-id>:<minute>} and expire on their own.
 *
 * <p>Design choice: if Redis is down we <em>fail open</em> (let the request through and log a
 * warning) so a cache outage doesn't take the whole API down. For a payments API you might
 * choose to fail closed instead. Either way, be ready to explain the trade-off.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration KEY_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;
    private final HrmsProperties.RateLimit limits;

    public RateLimitFilter(StringRedisTemplate redis, HrmsProperties.RateLimit limits) {
        this.redis = redis;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (CurrentUser.authentication().isEmpty()) {
            chain.doFilter(request, response); // unauthenticated requests are rejected later anyway
            return;
        }

        boolean assistant = request.getRequestURI().startsWith("/api/assistant/");
        int limit = assistant ? limits.assistantRequestsPerMinute() : limits.requestsPerMinute();
        long epochSecond = Instant.now().getEpochSecond();
        long window = epochSecond / 60;
        String key = "rl:%s:%s:%d".formatted(assistant ? "ai" : "api", CurrentUser.subject(), window);

        Optional<Long> count = increment(key);
        if (count.isEmpty()) {
            chain.doFilter(request, response); // fail open, see class comment
            return;
        }

        long remaining = Math.max(0, limit - count.get());
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (count.get() > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(60 - (epochSecond % 60)));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,\
                    "detail":"Rate limit exceeded. Try again later."}""");
            return;
        }
        chain.doFilter(request, response);
    }

    private Optional<Long> increment(String key) {
        try {
            // SET NX with a TTL first, so the key can never be left without an expiry.
            redis.opsForValue().setIfAbsent(key, "0", KEY_TTL);
            return Optional.ofNullable(redis.opsForValue().increment(key));
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
