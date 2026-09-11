package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SpaRoutes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controller helpers: the tenant every query is scoped by, and the page
 * size.
 *
 * <p>The tenant is the caller's own customer - the token's claim, or the
 * {@code X-Customer-Id} header in dev - unless an <em>operator</em> asks to
 * act as another customer with {@value #ACTING_HEADER}. That header is the
 * console's "View as": the operator keeps their own identity ({@code /me}
 * reports both) while every query runs against the chosen tenant. It is
 * <em>read-only</em>: only GET and HEAD honour it, so an operator looking
 * at a tenant cannot change that tenant's rules by accident or otherwise;
 * a write with the header is a 403. Every request that uses it is logged
 * with the operator's identity, because the access log at the edge only
 * ever sees the operator's own tenant. An ordinary user sending it is
 * refused with a 403 rather than ignored, so a client bug cannot silently
 * show the wrong tenant's data.
 */
@Component
public class RequestScope {

    /** Operators only: run this request as the named customer. */
    public static final String ACTING_HEADER = "X-Acting-Customer-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestScope.class);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");
    /** The shape of customers.customer_id (VARCHAR(64)); anything else is not a tenant. */
    static final Pattern CUSTOMER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,63}");

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private final CurrentCustomer currentCustomer;

    public RequestScope(CurrentCustomer currentCustomer) {
        this.currentCustomer = currentCustomer;
    }

    /** The tenant this request's queries are scoped by. */
    public String customerId(HttpServletRequest request) {
        String own = ownCustomerId();
        return actingAs(request).orElse(own);
    }

    /** The caller's own tenant, whoever they are acting as. */
    public String ownCustomerId() {
        return currentCustomer.customerId().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "no customer for this request (auth disabled? send X-Customer-Id)"));
    }

    /** The customer an operator is acting as, if the request asks for one. */
    public Optional<String> actingAs(HttpServletRequest request) {
        String header = request.getHeader(ACTING_HEADER);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        if (!currentCustomer.isOperator()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only operators may act as another customer");
        }
        if (!READ_METHODS.contains(request.getMethod())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "viewing as another customer is read-only; drop " + ACTING_HEADER + " to change your own rules");
        }
        String acting = header.trim();
        if (!CUSTOMER_ID.matcher(acting).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ACTING_HEADER + " is not a customer id");
        }
        log.info("acting-as operator={} own={} as={} {} {}",
                currentCustomer.subject().orElse("dev"), currentCustomer.customerId().orElse("?"), acting,
                request.getMethod(), SpaRoutes.decodedPath(request));
        return Optional.of(acting);
    }

    public int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }
}
