package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SpaRoutesTest {

    @Test
    void pagesAreDotlessPathsOutsideTheApiAndTheActuator() {
        assertThat(SpaRoutes.isSpaRoute("/")).isTrue();
        assertThat(SpaRoutes.isSpaRoute("/alerts")).isTrue();
        assertThat(SpaRoutes.isSpaRoute("/rules/42")).isTrue();
        assertThat(SpaRoutes.isSpaRoute("alerts")).isTrue();
        assertThat(SpaRoutes.isSpaRoute("/apiary")).isTrue();

        assertThat(SpaRoutes.isSpaRoute("/api")).isFalse();
        assertThat(SpaRoutes.isSpaRoute("/api/v1/anything")).isFalse();
        assertThat(SpaRoutes.isSpaRoute("api/v1/anything")).isFalse();
        assertThat(SpaRoutes.isSpaRoute("/actuator")).isFalse();
        assertThat(SpaRoutes.isSpaRoute("/actuator/env")).isFalse();
        assertThat(SpaRoutes.isSpaRoute("/assets/index.js")).isFalse();
        assertThat(SpaRoutes.isSpaRoute(null)).isFalse();
    }

    @Test
    void theDecodedPathIsWhatIsClassified() {
        MockHttpServletRequest encoded = new MockHttpServletRequest("GET", "/%61pi/v1/operator/customers");
        assertThat(SpaRoutes.decodedPath(encoded)).isEqualTo("/api/v1/operator/customers");
        assertThat(SpaRoutes.isSpaRoute(SpaRoutes.decodedPath(encoded))).isFalse();

        MockHttpServletRequest matrix = new MockHttpServletRequest("GET", "/api;x=1/v1/me");
        assertThat(SpaRoutes.isSpaRoute(SpaRoutes.decodedPath(matrix))).isFalse();
    }
}
