package com.auditflow.gateway.security;

import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enforced mode, end to end through the real filter chain and the real
 * JWKS fetch: only a correctly signed, unexpired Cognito ID token for our
 * app client that names a customer gets through.
 */
@SpringBootTest(properties = "audit.auth.enabled=true")
@AutoConfigureMockMvc
class CognitoJwtAuthTest {

    @DynamicPropertySource
    static void cognito(DynamicPropertyRegistry registry) {
        registry.add("audit.auth.issuer-uri", () -> TestJwks.ISSUER);
        registry.add("audit.auth.jwk-set-uri", () -> TestJwks.JWKS_URI);
        registry.add("audit.auth.client-id", () -> TestJwks.CLIENT_ID);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validIdTokenIsAcceptedAndScopesTheCustomer() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme").build());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("acme"))
                .andExpect(jsonPath("$.subject").value("user-42"));
    }

    @Test
    void missingTokenIs401() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    void devHeaderIsIgnoredWhenAuthIsEnforced() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(CurrentCustomer.DEV_HEADER, "acme"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        JWTClaimsSet claims = TestJwks.idTokenClaims("acme")
                .expirationTime(Date.from(Instant.now().minusSeconds(600)))
                .build();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TestJwks.sign(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        JWTClaimsSet claims = TestJwks.idTokenClaims("acme")
                .issuer("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_OTHERPOOL")
                .build();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TestJwks.sign(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForAnotherAppClientIs401() throws Exception {
        JWTClaimsSet claims = TestJwks.idTokenClaims("acme").audience("some-other-client").build();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TestJwks.sign(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenIs401BecauseItCarriesNoCustomer() throws Exception {
        JWTClaimsSet claims = TestJwks.idTokenClaims("acme").claim("token_use", "access").build();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TestJwks.sign(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void idTokenWithoutCustomerIs401() throws Exception {
        JWTClaimsSet claims = TestJwks.idTokenClaims("acme").claim("custom:customer_id", null).build();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TestJwks.sign(claims)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedByAKeyNotInTheJwksIs401() throws Exception {
        String token = TestJwks.signWithUnknownKey(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedTokenIs401() throws Exception {
        String token = TestJwks.unsigned(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonApiPathsAreDenied() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(get("/anything-else").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
