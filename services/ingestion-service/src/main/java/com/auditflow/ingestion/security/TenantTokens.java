package com.auditflow.ingestion.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ingestion tokens, each bound to the one tenant it may write as.
 *
 * <p>A single shared secret was the earlier design, and it made the token
 * an authenticator with nothing to authorise: any holder could post events
 * carrying <em>any</em> customerId. One compromised source - or one
 * customer given the token so they could emit their own events - could
 * forge or poison another customer's audit trail, which is the one thing
 * an audit trail may not permit.
 *
 * <p>Configured as {@code tenant=token,tenant=token}. Only the first
 * {@code =} splits, so a base64 token keeps its padding.
 *
 * <p>Tokens are held as SHA-256 digests and compared with
 * {@link MessageDigest#isEqual}: fixed 32-byte comparisons, so neither the
 * length nor a shared prefix of a real token can be read off the timing.
 * {@link #resolve} deliberately checks every entry rather than returning at
 * the first match, so the answer does not arrive sooner for a tenant that
 * happens to be listed first.
 */
public final class TenantTokens {

    private static final TenantTokens OPEN = new TenantTokens(Map.of());

    private final Map<String, byte[]> digestByTenant;

    private TenantTokens(Map<String, byte[]> digestByTenant) {
        this.digestByTenant = digestByTenant;
    }

    /**
     * @param spec {@code tenant=token[,tenant=token...]}; blank means open
     * @throws IllegalArgumentException on a malformed or duplicated entry,
     *         at startup rather than on the first request
     */
    public static TenantTokens parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return OPEN;
        }
        Map<String, byte[]> digests = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException(
                        "audit.ingestion.tokens entry is not tenant=token: '" + redact(trimmed) + "'");
            }
            String tenant = trimmed.substring(0, separator).trim();
            String token = trimmed.substring(separator + 1).trim();
            if (tenant.isEmpty() || token.isEmpty()) {
                throw new IllegalArgumentException(
                        "audit.ingestion.tokens entry has a blank tenant or token: '" + redact(trimmed) + "'");
            }
            if (digests.putIfAbsent(tenant, sha256(token)) != null) {
                throw new IllegalArgumentException(
                        "audit.ingestion.tokens lists tenant '" + tenant + "' twice");
            }
        }
        return digests.isEmpty() ? OPEN : new TenantTokens(Collections.unmodifiableMap(digests));
    }

    /** No tokens configured: every request is accepted and bound to no tenant. Local development only. */
    public boolean isOpen() {
        return digestByTenant.isEmpty();
    }

    public Set<String> tenants() {
        return digestByTenant.keySet();
    }

    /** @return the tenant this token belongs to, or empty if it belongs to none */
    public Optional<String> resolve(String presented) {
        byte[] presentedDigest = sha256(presented == null ? "" : presented);
        String match = null;
        for (Map.Entry<String, byte[]> entry : digestByTenant.entrySet()) {
            // no early exit: every request costs the same number of comparisons
            if (MessageDigest.isEqual(entry.getValue(), presentedDigest)) {
                match = entry.getKey();
            }
        }
        return Optional.ofNullable(match);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Never put a configured secret in an exception message. */
    private static String redact(String entry) {
        int separator = entry.indexOf('=');
        return separator < 0 ? "<no '=' in entry>" : entry.substring(0, separator + 1) + "...";
    }
}
