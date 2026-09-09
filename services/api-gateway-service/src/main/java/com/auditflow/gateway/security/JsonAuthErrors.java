package com.auditflow.gateway.security;

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
 * a 401 or a 403 from it. These two write the same
 * {@code {"error": ..., "message": ...}} body so the console parses one
 * shape everywhere. The 401 keeps the {@code WWW-Authenticate} header
 * the bearer entry point sets (RFC 6750), for clients that look for it.
 */
public final class JsonAuthErrors {

    private static final BearerTokenAuthenticationEntryPoint BEARER = new BearerTokenAuthenticationEntryPoint();

    private JsonAuthErrors() {
    }

    public static AuthenticationEntryPoint entryPoint() {
        return (request, response, exception) -> {
            BEARER.commence(request, response, exception);
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated",
                    "sign in and send a bearer token");
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_FORBIDDEN,
                "forbidden", "you are not allowed to do that");
    }

    static void write(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }
}
