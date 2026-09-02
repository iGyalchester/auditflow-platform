package com.auditflow.gateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * The Cognito-specific checks that Spring's default validator (signature,
 * expiry, issuer) does not cover:
 *
 * <ol>
 *   <li>{@code token_use} must be {@code id}. Cognito puts custom attributes -
 *       including {@code custom:customer_id}, the tenant key everything is
 *       scoped by - only in ID tokens; an access token carries no customer,
 *       so it cannot be authorized for anything here. This matches the
 *       API Gateway JWT authorizer in front of the service, which validates
 *       {@code aud}, a claim only ID tokens carry.</li>
 *   <li>{@code aud} must contain our app client id - a valid token from the
 *       same user pool but a different app is still the wrong token.</li>
 *   <li>{@code custom:customer_id} must be present and non-blank, so no
 *       request ever reaches a controller without a tenant.</li>
 * </ol>
 */
public class CognitoTokenValidator implements OAuth2TokenValidator<Jwt> {

    public static final String CUSTOMER_CLAIM = "custom:customer_id";
    static final String TOKEN_USE_CLAIM = "token_use";

    private final String clientId;

    public CognitoTokenValidator(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!"id".equals(token.getClaimAsString(TOKEN_USE_CLAIM))) {
            return fail("token_use must be 'id' (access tokens carry no customer_id)");
        }
        List<String> audience = token.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            return fail("token was not issued to this app client");
        }
        String customerId = token.getClaimAsString(CUSTOMER_CLAIM);
        if (customerId == null || customerId.isBlank()) {
            return fail("token has no " + CUSTOMER_CLAIM + " claim");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String reason) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", reason, null));
    }
}
