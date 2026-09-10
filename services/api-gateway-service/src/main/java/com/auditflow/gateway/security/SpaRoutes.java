package com.auditflow.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * The one definition of "is this path a page of the console?" - used by
 * the security chain (to permit it) and by {@code config/SpaConfig} (to
 * answer it with the shell). A page is a GET whose path has no dot and is
 * not the API or the actuator, including the bare prefixes ({@code /api}
 * is not a page either).
 *
 * <p>Always decide on the <em>decoded</em> path. Spring MVC dispatches on
 * the decoded path, so a rule that looked at the raw URI would classify
 * {@code /%61pi/v1/operator/customers} as a page and let it through to
 * the operator controller; {@link #decodedPath(HttpServletRequest)} is what
 * the matchers must use.
 */
public final class SpaRoutes {

    private SpaRoutes() {
    }

    public static boolean isSpaRoute(String decodedPath) {
        if (decodedPath == null) {
            return false;
        }
        String path = decodedPath.startsWith("/") ? decodedPath : "/" + decodedPath;
        if (path.equals("/api") || path.startsWith("/api/")) {
            return false;
        }
        if (path.equals("/actuator") || path.startsWith("/actuator/")) {
            return false;
        }
        return !path.contains(".");
    }

    /** The decoded path within the application, semicolon content removed - what MVC routes on. */
    public static String decodedPath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
