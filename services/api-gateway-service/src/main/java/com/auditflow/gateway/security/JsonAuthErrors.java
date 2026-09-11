package com.auditflow.gateway.security;

import com.auditflow.gateway.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The security filter chain answers before any controller runs, so the
 * {@code @RestControllerAdvice} that shapes every other error never sees
 * a 401 or a 403 from it. These two write the same {@link ApiError} body
 * (through the same Jackson mapper, so the shape cannot drift) with a hint
 * that fits the chain: the enforced chain asks for a bearer token, the
 * open chain for the dev headers. The enforced 401 keeps whatever the
 * bearer entry point decided - its {@code WWW-Authenticate} header (RFC
 * 6750) and its status, which is a 400 for a malformed header rather than
 * a 401.
 */
public final class JsonAuthErrors {

    private static final BearerTokenAuthenticationEntryPoint BEARER = new BearerTokenAuthenticationEntryPoint();

    private final ObjectMapper mapper;

    public JsonAuthErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** For the enforced chain: the bearer entry point's verdict, plus the JSON body. */
    public AuthenticationEntryPoint bearerEntryPoint() {
        return (request, response, exception) -> {
            BEARER.commence(request, response, exception);
            int status = response.getStatus() >= 400 ? response.getStatus() : HttpServletResponse.SC_UNAUTHORIZED;
            String message = switch (status) {
                case 400 -> "the Authorization header could not be read; send 'Bearer <Cognito ID token>'";
                case 403 -> "this token does not allow that";
                default -> "sign in and send a Cognito ID token as 'Authorization: Bearer <token>'";
            };
            write(response, status, codeFor(status), message);
        };
    }

    /** For the open chain: no bearer semantics, the remedy is the dev headers. */
    public AuthenticationEntryPoint devEntryPoint() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_UNAUTHORIZED,
                "unauthenticated", "auth is open: send X-Customer-Id (and X-Roles: operator for the operator view)");
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_FORBIDDEN,
                "forbidden", "you are not allowed to do that");
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 403 -> "forbidden";
            default -> "unauthenticated";
        };
    }

    void write(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ApiError.of(error, message));
        response.getWriter().flush();
    }
}
