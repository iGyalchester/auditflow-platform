package com.auditflow.gateway.config;

import com.auditflow.gateway.security.SpaRoutes;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the console. The Maven {@code frontend} profile copies the Vite
 * bundle into {@code classpath:/static/}, so {@code /assets/*.js},
 * {@code /index.html} and friends are ordinary static files. Every other
 * GET that is not a file (no dot in the path: {@code /alerts},
 * {@code /rules/42}) is answered with {@code index.html} too, because those
 * are the app's own routes and the browser must get the shell before React
 * can pick the page. Controllers ({@code /api/**}, {@code /actuator/**})
 * are matched before static resources, so nothing here shadows them, and
 * an unmatched path under either prefix stays a 404 rather than becoming a
 * page - a typo in an API URL must not return HTML with a 200. A path with
 * a dot that does not exist is a 404 for the same reason.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    static final String SHELL = "/static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // the base class also checks the resolved file is inside
                        // the location; keep that even though the handler already
                        // rejects ".." and friends
                        Resource requested = super.getResource(resourcePath, location);
                        if (requested != null) {
                            return requested;
                        }
                        if (!SpaRoutes.isSpaRoute(resourcePath)) {
                            return null;
                        }
                        Resource shell = new ClassPathResource(SHELL);
                        return shell.exists() ? shell : null;
                    }
                });
    }
}
