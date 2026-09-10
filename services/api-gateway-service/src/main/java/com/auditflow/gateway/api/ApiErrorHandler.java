package com.auditflow.gateway.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every failure that reaches Spring MVC into an {@link ApiError}.
 * Before this the gateway answered with Spring's default body - a status
 * and a timestamp, no message - so a rejected rule condition was a bare
 * 400 and the reason stayed in the server log. The two failures the
 * security filter chain answers itself (401 and 403 before any controller
 * runs) write the same shape from
 * {@code security/JsonAuthErrors}; the rate limiter's 429 is a plain
 * response with {@code Retry-After} and stays as it is.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** Controllers throw these with a status and a reason; the reason becomes the message. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> statusException(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        String message = e.getReason() == null ? HttpStatus.valueOf(status.value()).getReasonPhrase() : e.getReason();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (status.value() == 429) {
            // the console waits this out and retries once, like the rate limiter's 429
            response.header("Retry-After", "2");
        }
        return response.body(ApiError.of(code(status), message));
    }

    /** {@code @Valid} on a request body: which fields, and what is wrong with each. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiError("validation", "some fields are invalid", fields));
    }

    /** Unparsable JSON, a date that is not a date, a missing required parameter. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        String message;
        if (e instanceof MethodArgumentTypeMismatchException m) {
            message = "'" + m.getName() + "' is not a valid " + describe(m.getRequiredType());
        } else if (e instanceof MissingServletRequestParameterException m) {
            message = "'" + m.getParameterName() + "' is required";
        } else {
            message = "the request body could not be read";
        }
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "you are not allowed to do that"));
    }

    /** A static file that does not exist, or an API path nothing handles. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", "no such path"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("method_not_allowed", e.getMethod() + " is not supported here"));
    }

    /**
     * Everything else. Two families are not "our side": Spring's own
     * request-level failures (415 for a wrong Content-Type, 406 for an
     * Accept nothing can satisfy, ...) carry their status as an
     * {@link ErrorResponse} and keep it; and a client that went away
     * mid-response cannot be answered at all, so that is rethrown for the
     * container to log quietly rather than counted as a 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception e) throws Exception {
        if (e instanceof AsyncRequestNotUsableException || e.getClass().getSimpleName().equals("ClientAbortException")) {
            log.debug("client went away: {}", e.toString());
            throw e;
        }
        if (e instanceof ErrorResponse spring) {
            HttpStatusCode status = spring.getStatusCode();
            String detail = spring.getBody().getDetail();
            return ResponseEntity.status(status).body(ApiError.of(code(status),
                    detail != null ? detail : HttpStatus.valueOf(status.value()).getReasonPhrase()));
        }
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal", "something went wrong on our side"));
    }

    static String code(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 401 -> "unauthenticated";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 413 -> "too_many_events";
            case 429 -> "rate_limited";
            default -> HttpStatus.valueOf(status.value()).getReasonPhrase().toLowerCase().replace(' ', '_');
        };
    }

    private static String describe(Class<?> type) {
        if (type == null) {
            return "value";
        }
        if (type == java.time.Instant.class) {
            return "timestamp (use ISO-8601, e.g. 2026-09-01T00:00:00Z)";
        }
        if (type.isEnum()) {
            return "value (one of " + java.util.Arrays.toString(type.getEnumConstants()) + ")";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "whole number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return type.getSimpleName().toLowerCase();
    }
}
