package com.auditflow.ingestion.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTokenFilterTest {

    @Test
    void emptyConfiguredTokensLeaveTheEndpointOpenAndUnbound() throws ServletException, IOException {
        MockHttpServletRequest request = post(null);

        assertThat(through(new IngestTokenFilter(""), request).getStatus()).isEqualTo(200);
        // nothing to bind to, so the controller sees no tenant and skips the check
        assertThat(request.getAttribute(IngestTokenFilter.TENANT_ATTRIBUTE)).isNull();
    }

    @Test
    void aMatchingTokenPassesAndNamesItsTenant() throws ServletException, IOException {
        IngestTokenFilter filter = new IngestTokenFilter("resistance=tok-a,acme=tok-b");

        MockHttpServletRequest resistance = post("tok-a");
        assertThat(through(filter, resistance).getStatus()).isEqualTo(200);
        assertThat(resistance.getAttribute(IngestTokenFilter.TENANT_ATTRIBUTE)).isEqualTo("resistance");

        MockHttpServletRequest acme = post("tok-b");
        assertThat(through(filter, acme).getStatus()).isEqualTo(200);
        assertThat(acme.getAttribute(IngestTokenFilter.TENANT_ATTRIBUTE)).isEqualTo("acme");
    }

    @Test
    void missingOrWrongTokenIs401AndBindsNothing() throws ServletException, IOException {
        IngestTokenFilter filter = new IngestTokenFilter("resistance=tok-a");

        MockHttpServletRequest missing = post(null);
        assertThat(through(filter, missing).getStatus()).isEqualTo(401);
        assertThat(missing.getAttribute(IngestTokenFilter.TENANT_ATTRIBUTE)).isNull();

        MockHttpServletRequest wrong = post("nope");
        assertThat(through(filter, wrong).getStatus()).isEqualTo(401);
        assertThat(wrong.getAttribute(IngestTokenFilter.TENANT_ATTRIBUTE)).isNull();
    }

    private static MockHttpServletRequest post(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        if (header != null) {
            request.addHeader(IngestTokenFilter.TOKEN_HEADER, header);
        }
        return request;
    }

    private static MockHttpServletResponse through(IngestTokenFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
