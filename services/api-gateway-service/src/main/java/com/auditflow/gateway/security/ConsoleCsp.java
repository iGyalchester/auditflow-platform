package com.auditflow.gateway.security;

import java.net.URI;

/**
 * The Content-Security-Policy the gateway sends with every response. The
 * console's Cognito session (ID, access and refresh tokens) lives in the
 * browser's sessionStorage, which any script running on this origin can
 * read, so the policy's job is to make sure only our own bundle runs:
 * scripts from this origin only, no inline script, no framing. Styles
 * allow inline because Recharts writes style attributes. The browser may
 * talk to this origin, to the Cognito issuer (discovery, JWKS) and to the
 * hosted UI (the token endpoint), and to nothing else.
 */
public final class ConsoleCsp {

    private ConsoleCsp() {
    }

    public static String policy(AuthProperties props) {
        StringBuilder connect = new StringBuilder("'self'");
        if (props.enabled()) {
            append(connect, origin(props.issuerUri()));
            append(connect, origin(props.hostedUiDomain()));
        }
        return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                + "font-src 'self'; connect-src " + connect + "; frame-ancestors 'none'; base-uri 'self'; "
                + "form-action 'self'; object-src 'none'";
    }

    private static void append(StringBuilder to, String origin) {
        if (origin != null && to.indexOf(origin) < 0) {
            to.append(' ').append(origin);
        }
    }

    /** {@code https://host[:port]} of a URL, or null when it is blank or unparsable. */
    static String origin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
