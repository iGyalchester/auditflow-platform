package com.auditflow.common.ratelimit;

/**
 * What a service's rate limit is configured to be.
 *
 * <p>A record rather than a set of {@code @Value} parameters because the
 * two services that use it bind different property prefixes to the same
 * shape: the gateway's {@code audit.rate-limit.*} and ingestion's
 * {@code audit.ingestion.rate-limit.*}. Each keeps its own small
 * configuration class naming its prefix and defaults; the filter itself
 * does not care where the numbers came from.
 *
 * @param enabled         off switch, mainly for tests
 * @param requestsPerSecond sustained refill rate per client
 * @param burst           bucket size, so a short spike is allowed
 * @param maxClients      cap on tracked keys, so the limiter cannot be
 *                        turned into a memory leak by rotating source IPs
 * @param clientIpHeader  header a trusted proxy sets to the real client
 *                        address, or blank to use the socket address.
 *                        Never X-Forwarded-For - see {@link ClientKeyResolver}.
 * @param pathPrefix      only requests under this prefix are limited
 */
public record RateLimitSettings(
        boolean enabled,
        double requestsPerSecond,
        long burst,
        int maxClients,
        String clientIpHeader,
        String pathPrefix) {
}
