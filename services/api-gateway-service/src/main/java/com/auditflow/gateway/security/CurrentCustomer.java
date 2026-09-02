package com.auditflow.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one place controllers ask "which customer is this?". With auth
 * enforced the answer is the verified {@code custom:customer_id} claim and
 * nothing else; with auth disabled (local dev) it is the
 * {@code X-Customer-Id} header. The header is never consulted when auth is
 * on, so a caller cannot pick a tenant by adding a header.
 */
@Component
public class CurrentCustomer {

    public static final String DEV_HEADER = "X-Customer-Id";

    private final boolean authEnabled;

    public CurrentCustomer(AuthProperties props) {
        this.authEnabled = props.enabled();
    }

    public Optional<String> customerId(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.ofNullable(jwt.getToken().getClaimAsString(CognitoTokenValidator.CUSTOMER_CLAIM))
                    .filter(id -> !id.isBlank());
        }
        if (!authEnabled) {
            return Optional.ofNullable(request.getHeader(DEV_HEADER)).filter(id -> !id.isBlank());
        }
        return Optional.empty();
    }

    public Optional<String> subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.ofNullable(jwt.getToken().getSubject());
        }
        return Optional.empty();
    }
}
