package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.DevHeaderAuthenticationFilter;
import com.auditflow.gateway.security.Roles;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenant a request is scoped by, with the security context populated
 * the way the open chain's filter populates it - no mocks, the real
 * filter and the real {@link CurrentCustomer}.
 */
class RequestScopeTest {

    private final RequestScope scope = new RequestScope(new CurrentCustomer());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest signedIn(String customerId, String roles, String actingAs) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit-logs");
        if (customerId != null) {
            request.addHeader(CurrentCustomer.DEV_HEADER, customerId);
        }
        if (roles != null) {
            request.addHeader(Roles.DEV_ROLES_HEADER, roles);
        }
        if (actingAs != null) {
            request.addHeader(RequestScope.ACTING_HEADER, actingAs);
        }
        FilterChain chain = new MockFilterChain();
        new DevHeaderAuthenticationFilter().doFilter(request, new MockHttpServletResponse(), chain);
        return request;
    }

    @Test
    void theCallersOwnCustomerByDefault() throws Exception {
        assertThat(scope.customerId(signedIn("acme", null, null))).isEqualTo("acme");
        assertThat(scope.actingAs(signedIn("acme", null, null))).isEmpty();
    }

    @Test
    void anOperatorMayActAsAnotherCustomer() throws Exception {
        MockHttpServletRequest request = signedIn("platform", "operator", "acme");
        assertThat(scope.customerId(request)).isEqualTo("acme");
        assertThat(scope.actingAs(request)).contains("acme");
    }

    @Test
    void roleHeaderIsCaseInsensitiveAndCommaSeparated() throws Exception {
        assertThat(scope.customerId(signedIn("platform", "viewer, OPERATOR", "acme"))).isEqualTo("acme");
    }

    @Test
    void aPlainUserSendingTheActingHeaderIs403() throws Exception {
        MockHttpServletRequest request = signedIn("other-co", null, "acme");
        assertThatThrownBy(() -> scope.customerId(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aBlankActingHeaderMeansNoActing() throws Exception {
        assertThat(scope.customerId(signedIn("other-co", null, "   "))).isEqualTo("other-co");
    }

    @Test
    void noCustomerIs400() throws Exception {
        MockHttpServletRequest request = signedIn(null, null, null);
        assertThatThrownBy(() -> scope.customerId(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void limitDefaultsAndBounds() {
        assertThat(scope.limit(null)).isEqualTo(100);
        assertThat(scope.limit(1000)).isEqualTo(1000);
        assertThatThrownBy(() -> scope.limit(0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> scope.limit(1001)).isInstanceOf(ResponseStatusException.class);
    }
}
