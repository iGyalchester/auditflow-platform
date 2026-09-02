package com.auditflow.gateway.security;

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
 * Per-client-IP token bucket in front of {@code /api/**}, ahead of
 * authentication so a brute-force or a runaway client is refused before
 * it costs a JWKS lookup or a database query. Behind the ALB the client
 * address is the first {@code X-Forwarded-For} hop, trusted only when
 * {@code audit.rate-limit.trust-forwarded-for} is on (the aws profile);
 * trusting it elsewhere would let any caller pick their own bucket.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final TokenBucketLimiter limiter;

    public RateLimitFilter(@Value("${audit.rate-limit.enabled:true}") boolean enabled,
                           @Value("${audit.rate-limit.requests-per-second:20}") double requestsPerSecond,
                           @Value("${audit.rate-limit.burst:40}") long burst,
                           @Value("${audit.rate-limit.max-tracked-clients:10000}") int maxClients,
                           @Value("${audit.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.limiter = new TokenBucketLimiter(burst, requestsPerSecond, maxClients);
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
