package com.auditflow.gateway.security;

import com.auditflow.common.ratelimit.ClientKeyResolver;
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
 * it costs a JWKS lookup or a database query.
 *
 * <p>Behind a proxy the socket address is the proxy, so every caller shares
 * one bucket. The fix is not to parse {@code X-Forwarded-For}: every hop
 * appends to it, so its first entry is whatever the client sent and a
 * caller could rotate it to escape the limit entirely, or forge somebody
 * else's to get them limited. Instead name a header a proxy you control
 * sets and overwrites - {@code audit.rate-limit.client-ip-header}, empty by
 * default. {@link ClientKeyResolver} holds that rule.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final ClientKeyResolver keyResolver;
    private final TokenBucketLimiter limiter;

    public RateLimitFilter(@Value("${audit.rate-limit.enabled:true}") boolean enabled,
                           @Value("${audit.rate-limit.requests-per-second:20}") double requestsPerSecond,
                           @Value("${audit.rate-limit.burst:40}") long burst,
                           @Value("${audit.rate-limit.max-tracked-clients:10000}") int maxClients,
                           @Value("${audit.rate-limit.client-ip-header:}") String clientIpHeader) {
        this.enabled = enabled;
        this.keyResolver = new ClientKeyResolver(clientIpHeader);
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
        String header = keyResolver.trustedHeader();
        return keyResolver.resolve(header == null ? null : request.getHeader(header),
                request.getRemoteAddr());
    }
}
