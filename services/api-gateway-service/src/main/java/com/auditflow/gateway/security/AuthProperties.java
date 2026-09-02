package com.auditflow.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the gateway verifies callers.
 *
 * <ul>
 *   <li>{@code enabled=false} (the default profile): every request is let
 *       through and the customer comes from an {@code X-Customer-Id} header -
 *       for local development only, mirroring the "empty token = open"
 *       convention of ingestion-service.</li>
 *   <li>{@code enabled=true} (the {@code aws} profile): every {@code /api/**}
 *       request must carry a Cognito ID token for our app client, signed by
 *       the pool's published keys. Missing issuer/client id fails startup.</li>
 * </ul>
 *
 * @param enabled    enforce JWT verification
 * @param issuerUri  {@code https://cognito-idp.<region>.amazonaws.com/<pool-id>}
 * @param jwkSetUri  where the pool publishes its signing keys; derived from the
 *                   issuer when blank
 * @param clientId   the Cognito app client id the token must be issued to
 */
@ConfigurationProperties(prefix = "audit.auth")
public record AuthProperties(boolean enabled, String issuerUri, String jwkSetUri, String clientId) {

    public String resolvedJwkSetUri() {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return jwkSetUri;
        }
        return issuerUri.replaceAll("/$", "") + "/.well-known/jwks.json";
    }

    void requireComplete() {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "audit.auth.enabled=true but audit.auth.issuer-uri (COGNITO_ISSUER_URI) is not set");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(
                    "audit.auth.enabled=true but audit.auth.client-id (COGNITO_CLIENT_ID) is not set");
        }
    }
}
