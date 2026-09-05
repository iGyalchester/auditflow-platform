package com.auditflow.common.ratelimit;

/**
 * Decides which string a rate limiter should count against: the socket's
 * address, or an address a trusted proxy told us about.
 *
 * <p>The rule that matters is what this class refuses to do. Parsing
 * {@code X-Forwarded-For} and taking its first entry looks right and is
 * wrong here: every hop <em>appends</em>, so the first entry is whatever
 * the original client sent - a value the client chooses. A caller could put
 * a fresh address there on every request and never be limited at all, or
 * put somebody else's there and get them limited instead.
 *
 * <p>So a header is only used when one is explicitly named (the deployment
 * knows a proxy sets it and overwrites anything the client sent), and only
 * when it carries a single value. A comma list means the header passed
 * through something that appends, which is exactly the header we must not
 * trust - so it falls back to the socket address.
 */
public final class ClientKeyResolver {

    private final String trustedHeader;

    /** @param trustedHeader header a trusted proxy sets; null or blank trusts nothing */
    public ClientKeyResolver(String trustedHeader) {
        this.trustedHeader = trustedHeader == null || trustedHeader.isBlank() ? null : trustedHeader.trim();
    }

    /** The header to read, or null when only the socket address is trusted. */
    public String trustedHeader() {
        return trustedHeader;
    }

    /**
     * @param headerValue value of {@link #trustedHeader()} on this request, or null
     * @param remoteAddr  the socket address
     */
    public String resolve(String headerValue, String remoteAddr) {
        if (trustedHeader == null || headerValue == null) {
            return remoteAddr;
        }
        String value = headerValue.trim();
        if (value.isEmpty() || value.indexOf(',') >= 0) {
            return remoteAddr;
        }
        return value;
    }
}
