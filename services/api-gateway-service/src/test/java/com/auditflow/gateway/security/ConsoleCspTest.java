package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleCspTest {

    @Test
    void openModeTalksToItselfOnly() {
        String policy = ConsoleCsp.policy(new AuthProperties(false, "https://ignored", "", "", ""));
        assertThat(policy).contains("connect-src 'self';").contains("script-src 'self';").contains("frame-ancestors 'none'");
    }

    @Test
    void enforcedModeAddsTheIssuerAndTheHostedUiOrigins() {
        String policy = ConsoleCsp.policy(new AuthProperties(true,
                "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_abc/", "", "client",
                "https://auditflow-prod.auth.eu-west-1.amazoncognito.com/"));
        assertThat(policy).contains("connect-src 'self' https://cognito-idp.eu-west-1.amazonaws.com "
                + "https://auditflow-prod.auth.eu-west-1.amazoncognito.com;");
    }

    @Test
    void originsAreSchemeHostPortOnly() {
        assertThat(ConsoleCsp.origin("https://a.example:8443/path?q=1")).isEqualTo("https://a.example:8443");
        assertThat(ConsoleCsp.origin("https://a.example")).isEqualTo("https://a.example");
        assertThat(ConsoleCsp.origin("not a url")).isNull();
        assertThat(ConsoleCsp.origin("")).isNull();
        assertThat(ConsoleCsp.origin(null)).isNull();
    }
}
