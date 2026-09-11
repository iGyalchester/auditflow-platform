package com.auditflow.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * The open chain's stand-in for a Cognito token: {@code X-Customer-Id}
 * names the caller's tenant and {@code X-Roles: operator} makes them an
 * operator. Registered only when {@code audit.auth.enabled=false}, so with
 * auth on these headers are never read - a caller cannot pick a tenant or
 * a role by adding them. A request without the customer header stays
 * anonymous, which the controllers turn into a 400 ("send X-Customer-Id").
 */
public class DevHeaderAuthenticationFilter extends OncePerRequestFilter {

    /** What the open chain puts in the security context. */
    public static final class DevAuthentication extends AbstractAuthenticationToken {

        private final String customerId;

        DevAuthentication(String customerId, boolean operator) {
            super(Roles.authorities(operator));
            this.customerId = customerId;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return customerId;
        }

        public String customerId() {
            return customerId;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String customerId = request.getHeader(CurrentCustomer.DEV_HEADER);
        if (customerId != null && !customerId.isBlank()) {
            SecurityContextHolder.getContext().setAuthentication(
                    new DevAuthentication(customerId.trim(), hasOperatorRole(request)));
        }
        chain.doFilter(request, response);
    }

    private static boolean hasOperatorRole(HttpServletRequest request) {
        String roles = request.getHeader(Roles.DEV_ROLES_HEADER);
        return roles != null && Arrays.stream(roles.split(","))
                .map(role -> role.trim().toLowerCase(Locale.ROOT))
                .anyMatch(Roles.OPERATOR.toLowerCase(Locale.ROOT)::equals);
    }
}
