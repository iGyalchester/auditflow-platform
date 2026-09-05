package com.auditflow.ingestion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates an event source by its X-Audit-Token header and records
 * <em>which tenant</em> that token belongs to, as a request attribute the
 * controller reads.
 *
 * <p>That second half is the point. The token used to be one shared secret,
 * so it proved only that the caller was some known source - and any source
 * could then post events under any customerId. Now a token names its tenant
 * and {@code SchemaValidator} refuses an event claiming a different one.
 *
 * <p>No tokens configured = open, for local development only. ALWAYS set
 * audit.ingestion.tokens anywhere the endpoint is reachable by anything you
 * do not control, or anyone can forge audit evidence.
 */
@Component
public class IngestTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IngestTokenFilter.class);

    public static final String TOKEN_HEADER = "X-Audit-Token";

    /** Request attribute holding the tenant the presented token belongs to. */
    public static final String TENANT_ATTRIBUTE = "auditflow.ingestion.tenant";

    private final TenantTokens tokens;

    @Autowired
    public IngestTokenFilter(@Value("${audit.ingestion.tokens:}") String tokensSpec) {
        this(TenantTokens.parse(tokensSpec));
    }

    IngestTokenFilter(TenantTokens tokens) {
        this.tokens = tokens;
        if (tokens.isOpen()) {
            log.warn("audit.ingestion.tokens is empty: the ingestion endpoint is OPEN and events are "
                    + "accepted for any customerId. Local development only.");
        } else {
            log.info("Ingestion tokens configured for {} tenant(s): {}",
                    tokens.tenants().size(), tokens.tenants());
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (tokens.isOpen()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> tenant = tokens.resolve(request.getHeader(TOKEN_HEADER));
        if (tenant.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
        chain.doFilter(request, response);
    }
}
