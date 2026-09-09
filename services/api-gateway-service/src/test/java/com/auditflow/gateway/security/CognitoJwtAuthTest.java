package com.auditflow.gateway.security;

import com.auditflow.gateway.controllers.RequestScope;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.auditflow.gateway.data.AuditLogRepository;
import com.auditflow.gateway.data.AlertHistoryRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enforced mode, end to end through the real filter chain and the real
 * JWKS fetch: only a correctly signed, unexpired Cognito ID token for our
 * app client that names a customer gets through.
 */
@SpringBootTest(properties = {"audit.auth.enabled=true", "spring.flyway.enabled=false",
        "management.health.db.enabled=false"})
@AutoConfigureMockMvc
class CognitoJwtAuthTest {

    @MockBean
    private AuditLogRepository auditLogRepository;
    @MockBean
    private AlertHistoryRepository alertHistoryRepository;
    @MockBean
    private com.auditflow.gateway.data.AlertRuleRepository alertRuleRepository;
    @MockBean
    private com.auditflow.gateway.data.CustomerRepository customerRepository;

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
                .andExpect(jsonPath("$.subject").value("user-42"))
                .andExpect(jsonPath("$.roles").value(contains("USER")))
                .andExpect(jsonPath("$.actingAs").doesNotExist());
    }

    @Test
    void membersOfTheOperatorsGroupGetTheOperatorRole() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme")
                .claim("cognito:groups", List.of("operators")).build());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(contains("USER", "OPERATOR")));
        // nothing is mounted there yet, so a 404 proves the role check let
        // the request through to MVC; a plain user gets 403 (below)
        mockMvc.perform(get("/api/v1/operator/customers").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void otherGroupsDoNotGrantTheOperatorRole() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme")
                .claim("cognito:groups", List.of("admins", "auditors")).build());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.roles").value(contains("USER")));
        mockMvc.perform(get("/api/v1/operator/customers").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anOperatorMayActAsAnotherCustomerButAUserMayNot() throws Exception {
        String operator = TestJwks.sign(TestJwks.idTokenClaims("platform")
                .claim("cognito:groups", List.of("operators")).build());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + operator)
                        .header(RequestScope.ACTING_HEADER, "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("platform"))
                .andExpect(jsonPath("$.actingAs").value("acme"));

        String user = TestJwks.sign(TestJwks.idTokenClaims("other-co").build());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user)
                        .header(RequestScope.ACTING_HEADER, "acme"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    /** The dev role header is read by a filter that only the open chain has. */
    @Test
    void devRoleHeaderIsIgnoredWhenAuthIsEnforced() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(get("/api/v1/operator/customers").header("Authorization", "Bearer " + token)
                        .header(Roles.DEV_ROLES_HEADER, "operator"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingTokenIs401WithTheSharedErrorShape() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("unauthenticated"))
                .andExpect(jsonPath("$.message").isString());
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

    /**
     * The console's files and its client-side routes are public GETs in
     * both modes: HTML and JavaScript hold no data. Everything else that is
     * not the API stays closed, whatever the method.
     */
    @Test
    void consoleShellAndRoutesAreOpenButNothingElseIs() throws Exception {
        // "/" is Boot's welcome page: a forward to index.html, which MockMvc
        // records rather than follows
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("test shell")));
        mockMvc.perform(get("/alerts/al-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("test shell")));
        mockMvc.perform(get("/callback").param("code", "x")).andExpect(status().isOk());
        mockMvc.perform(get("/config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.issuerUri").value(TestJwks.ISSUER))
                .andExpect(jsonPath("$.clientId").value(TestJwks.CLIENT_ID));

        mockMvc.perform(get("/assets/missing.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(post("/anything-else")).andExpect(status().isUnauthorized());
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(post("/anything-else").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** An API path nothing handles is a JSON 404, never the HTML shell with a 200. */
    @Test
    void unknownApiPathsAreNotTheShell() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(get("/api/v1/nothing-here").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    /**
     * The internal ALB probes this and has no token to present. It is the
     * one open path in enforced mode; the two tests below pin both halves of
     * that - it really is open, and nothing else about actuator is.
     */
    @Test
    void healthIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details=never: no datasource URL, no component list
                // leaking to an unauthenticated caller.
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void livenessIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherActuatorPathsStayClosed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherActuatorPathsStayClosedEvenWithAValidToken() throws Exception {
        String token = TestJwks.sign(TestJwks.idTokenClaims("acme").build());
        mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
