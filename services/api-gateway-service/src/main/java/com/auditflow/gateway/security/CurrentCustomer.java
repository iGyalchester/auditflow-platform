package com.auditflow.gateway.security;

import com.auditflow.gateway.security.DevHeaderAuthenticationFilter.DevAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * The one place controllers ask "who is this?". With auth enforced the
 * answer is the verified {@code custom:customer_id} claim and the token's
 * groups; with auth disabled (local dev) it is what
 * {@link DevHeaderAuthenticationFilter} read from the {@code X-Customer-Id}
 * and {@code X-Roles} headers. Those headers are never consulted when auth
 * is on, because the filter that reads them is not in that chain.
 */
@Component
public class CurrentCustomer {

    public static final String DEV_HEADER = "X-Customer-Id";

    /** The caller's own tenant. */
    public Optional<String> customerId() {
        Authentication auth = authentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.ofNullable(jwt.getToken().getClaimAsString(CognitoTokenValidator.CUSTOMER_CLAIM))
                    .filter(id -> !id.isBlank());
        }
        if (auth instanceof DevAuthentication dev) {
            return Optional.of(dev.customerId());
        }
        return Optional.empty();
    }

    public Optional<String> subject() {
        Authentication auth = authentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.ofNullable(jwt.getToken().getSubject());
        }
        return Optional.empty();
    }

    /** {@code USER}, plus {@code OPERATOR} for platform operators; empty when anonymous. */
    public Set<String> roles() {
        Authentication auth = authentication();
        if (auth instanceof JwtAuthenticationToken || auth instanceof DevAuthentication) {
            return Roles.names(auth.getAuthorities());
        }
        return Set.of();
    }

    public boolean isOperator() {
        return roles().contains(Roles.OPERATOR);
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
