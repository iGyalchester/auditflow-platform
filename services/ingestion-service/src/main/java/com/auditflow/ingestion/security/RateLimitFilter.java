package com.auditflow.ingestion.security;

import com.auditflow.common.ratelimit.TokenBucketLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-source-IP token bucket on the ingestion endpoint. Sources are few
 * and chatty (the collector agent posts one request per event), so the
 * defaults are generous; the point is that a misbehaving or compromised
 * source cannot saturate Kafka for everyone else. A limited request is a
 * 429 with Retry-After, which the agent treats like any non-2xx: hold the
 * checkpoint and retry - nothing is lost.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final TokenBucketLimiter limiter;

    public RateLimitFilter(@Value("${audit.ingestion.rate-limit.enabled:true}") boolean enabled,
                           @Value("${audit.ingestion.rate-limit.requests-per-second:200}") double requestsPerSecond,
                           @Value("${audit.ingestion.rate-limit.burst:500}") long burst,
                           @Value("${audit.ingestion.rate-limit.max-tracked-sources:1000}") int maxSources,
                           @Value("${audit.ingestion.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.limiter = new TokenBucketLimiter(burst, requestsPerSecond, maxSources);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(clientKey(request));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"rate limit exceeded\",\"retryAfterSeconds\":"
                    + decision.retryAfterSeconds() + "}");
            return;
        }
        chain.doFilter(request, response);
    }

    String clientKey(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
