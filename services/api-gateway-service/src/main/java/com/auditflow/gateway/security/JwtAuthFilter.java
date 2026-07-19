package com.auditflow.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;

/**
 * Extracts the bearer token from incoming requests. Signature/claims
 * verification against Cognito's JWKS endpoint is not implemented yet -
 * this only makes the token available to downstream handlers via a
 * request attribute.
 */
@Component
public class JwtAuthFilter extends GenericFilterBean {

    public static final String TOKEN_ATTRIBUTE = "auditflow.bearerToken";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            String authHeader = httpRequest.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                request.setAttribute(TOKEN_ATTRIBUTE, authHeader.substring("Bearer ".length()));
            }
        }
        chain.doFilter(request, response);
    }
}
