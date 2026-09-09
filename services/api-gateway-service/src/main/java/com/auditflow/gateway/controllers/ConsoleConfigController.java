package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.AuthProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the console needs to know before anyone signs in, so one built
 * bundle works in every environment: whether auth is on, and if so which
 * Cognito pool and app client to send the browser to. Public and free of
 * secrets - a public client has no secret, and the issuer is in every
 * token anyway.
 */
@RestController
public class ConsoleConfigController {

    /**
     * @param authEnabled    false = the dev sign-in page (customer id + role headers)
     * @param issuerUri      the OIDC authority ({@code https://cognito-idp.<region>.amazonaws.com/<pool>})
     * @param clientId       the public app client the console signs in with
     * @param hostedUiDomain the hosted UI origin, for sign-out
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ConsoleConfig(boolean authEnabled, String issuerUri, String clientId, String hostedUiDomain) {
    }

    private final AuthProperties props;

    public ConsoleConfigController(AuthProperties props) {
        this.props = props;
    }

    @GetMapping(value = "/config.json", produces = "application/json")
    public ConsoleConfig config() {
        if (!props.enabled()) {
            return new ConsoleConfig(false, null, null, null);
        }
        return new ConsoleConfig(true, props.issuerUri(), props.clientId(), props.hostedUiDomain());
    }
}
