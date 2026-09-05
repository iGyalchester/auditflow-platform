package com.auditflow.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-client token bucket, ahead of authentication so a brute-force or a
 * runaway client is refused before it costs a JWKS lookup, a database query
 * or a Kafka publish. A limited request is a 429 with Retry-After.
 *
 * <p>One filter for both services. They previously had a copy each,
 * differing only in property prefix and defaults - which is the shape of
 * duplication that stays in sync right up until it matters: the
 * X-Forwarded-For decision below had to be got right twice.
 *
 * <p>Behind a proxy the socket address is the proxy, so every caller shares
 * one bucket. The fix is not to parse {@code X-Forwarded-For}: every hop
 * appends to it, so its first entry is whatever the client sent, and a
 * caller could rotate it to escape the limit entirely or forge somebody
 * else's to get them limited. Instead the deployment names a header its own
 * proxy sets and overwrites. {@link ClientKeyResolver} holds that rule.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitSettings settings;
    private final ClientKeyResolver keyResolver;
    private final TokenBucketLimiter limiter;

    public RateLimitFilter(RateLimitSettings settings) {
        this.settings = settings;
        this.keyResolver = new ClientKeyResolver(settings.clientIpHeader());
        this.limiter = new TokenBucketLimiter(
                settings.burst(), settings.requestsPerSecond(), settings.maxClients());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !settings.enabled() || !request.getRequestURI().startsWith(settings.pathPrefix());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(clientKey(request));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded\",\"retryAfterSeconds\":"
                    + decision.retryAfterSeconds() + "}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Package-private for the test; the resolver's rule is the interesting part. */
    String clientKey(HttpServletRequest request) {
        String header = keyResolver.trustedHeader();
        return keyResolver.resolve(header == null ? null : request.getHeader(header),
                request.getRemoteAddr());
    }
}
