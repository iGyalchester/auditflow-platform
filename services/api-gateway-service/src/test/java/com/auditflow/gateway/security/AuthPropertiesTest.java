package com.auditflow.gateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPropertiesTest {

    @Test
    void jwksUriDerivesFromIssuerWhenNotSet() {
        AuthProperties props = new AuthProperties(true,
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc/", "", "client");
        assertThat(props.resolvedJwkSetUri())
                .isEqualTo("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc/.well-known/jwks.json");
    }

    @Test
    void enforcedModeFailsFastWithoutIssuerOrClient() {
        assertThatThrownBy(() -> new AuthProperties(true, "", "", "client").requireComplete())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COGNITO_ISSUER_URI");
        assertThatThrownBy(() -> new AuthProperties(true, "https://issuer", "", "").requireComplete())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COGNITO_CLIENT_ID");
    }
}
