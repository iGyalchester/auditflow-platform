package com.auditflow.ingestion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-secret gate for the ingestion API: every event source (an
 * application emitter, a collector agent) must present the same
 * X-Audit-Token header. Empty token = open, for local development only -
 * ALWAYS set audit.ingestion.token when the endpoint is reachable by
 * anything you don't control, or anyone can forge audit evidence.
 * Comparison is constant-time (MessageDigest.isEqual) so the token can't
 * be guessed byte-by-byte from response timing.
 */
@Component
public class IngestTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Audit-Token";

    private final byte[] expectedToken;

    public IngestTokenFilter(@Value("${audit.ingestion.token:}") String token) {
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expectedToken.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader(TOKEN_HEADER);
        byte[] presentedBytes = presented == null
                ? new byte[0]
                : presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, presentedBytes)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
