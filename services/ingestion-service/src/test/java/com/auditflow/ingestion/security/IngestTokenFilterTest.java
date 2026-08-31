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
    void emptyConfiguredTokenLeavesTheEndpointOpen() throws ServletException, IOException {
        MockHttpServletResponse response = passThrough(new IngestTokenFilter(""), null);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void matchingTokenPasses() throws ServletException, IOException {
        MockHttpServletResponse response = passThrough(new IngestTokenFilter("s3cret"), "s3cret");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingOrWrongTokenIs401() throws ServletException, IOException {
        assertThat(passThrough(new IngestTokenFilter("s3cret"), null).getStatus()).isEqualTo(401);
        assertThat(passThrough(new IngestTokenFilter("s3cret"), "wrong").getStatus()).isEqualTo(401);
    }

    private MockHttpServletResponse passThrough(IngestTokenFilter filter, String header)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        if (header != null) {
            request.addHeader(IngestTokenFilter.TOKEN_HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
