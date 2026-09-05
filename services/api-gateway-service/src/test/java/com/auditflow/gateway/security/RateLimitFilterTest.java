package com.auditflow.gateway.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private static MockHttpServletRequest request(String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void burstThen429WithRetryAfterPerClient() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 2, 100, "");
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(request("/api/v1/alerts", "10.0.0.1"), ok, chain);
            assertThat(ok.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/alerts", "10.0.0.1"), limited, chain);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(limited.getContentAsString()).contains("rate limit exceeded");
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // a different client is unaffected
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/alerts", "10.0.0.2"), other, chain);
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    void clientIpHeaderIsUsedOnlyWhenConfigured_andForwardedForNever() {
        MockHttpServletRequest request = request("/api/v1/me", "10.9.9.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.5");
        request.addHeader("X-Client-IP", "198.51.100.4");

        // nothing configured: the socket address, whatever the headers claim
        assertThat(new RateLimitFilter(true, 1, 1, 10, "").clientKey(request)).isEqualTo("10.9.9.9");

        // the named header, set by a proxy we control
        assertThat(new RateLimitFilter(true, 1, 1, 10, "X-Client-IP").clientKey(request))
                .isEqualTo("198.51.100.4");

        // X-Forwarded-For is a comma list because every hop appends, so its
        // leading entry is client-supplied - never usable as a limit key,
        // even if someone points the setting straight at it
        assertThat(new RateLimitFilter(true, 1, 1, 10, "X-Forwarded-For").clientKey(request))
                .isEqualTo("10.9.9.9");
    }

    @Test
    void disabledOrNonApiPathsAreNotLimited() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        RateLimitFilter disabled = new RateLimitFilter(false, 1, 1, 10, "");
        for (int i = 0; i < 5; i++) {
            disabled.doFilter(request("/api/v1/alerts", "10.0.0.1"), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // A path outside /api/ is not limited however often it is called.
        // A fresh chain, so the count is only about these two requests - the
        // assertion this replaces used a matcher that could never match, so
        // never() was satisfied whatever the filter did.
        FilterChain healthChain = mock(FilterChain.class);
        RateLimitFilter enabled = new RateLimitFilter(true, 1, 1, 10, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        enabled.doFilter(request("/actuator/health", "10.0.0.1"), response, healthChain);
        enabled.doFilter(request("/actuator/health", "10.0.0.1"), response, healthChain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(healthChain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
