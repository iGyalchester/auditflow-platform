package com.auditflow.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Stateless security for the gateway. Two mutually exclusive modes
 * selected by {@code audit.auth.enabled} - see {@link AuthProperties}.
 * Session cookies and CSRF are off in both: the API is called with a
 * bearer token (or, locally, a header), never a browser form.
 *
 * <p>Both chains share one authorization table, {@link #authorize}:
 * <ul>
 *   <li>the health probe is open (the internal ALB has no token);</li>
 *   <li>the console's files and its client-side routes are open GETs -
 *       the HTML and JavaScript hold no data, every number on a page
 *       comes from {@code /api/**} with a token;</li>
 *   <li>{@code /api/v1/operator/**} needs the operator role;</li>
 *   <li>{@code /api/**} needs a verified token when auth is on (open mode
 *       leaves it to the controllers, which answer 400 without a customer);</li>
 *   <li>everything else is denied.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Files the Vite build emits and the OIDC callback route, all public. */
    static final String[] CONSOLE_FILES = {"/", "/index.html", "/favicon.svg", "/config.json", "/assets/**", "/callback"};

    /**
     * A client-side route: a GET whose path has no dot and is not an API
     * or actuator path ({@code /alerts}, {@code /rules/42}). SpaConfig
     * answers these with the shell so a reload deep in the app works.
     */
    static final RequestMatcher SPA_ROUTE = request -> {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.GET.matches(request.getMethod())
                && !path.startsWith("/api/") && !path.startsWith("/actuator")
                && !path.contains(".");
    };

    static void authorize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
                          boolean enforced) {
        // The internal ALB probes health and has no token. Only that path
        // is open; show-details=never keeps the body a bare {"status":"UP"}.
        // Every other actuator path is closed in both modes.
        auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers(HttpMethod.GET, CONSOLE_FILES).permitAll()
                .requestMatchers(SPA_ROUTE).permitAll()
                .requestMatchers("/api/v1/operator/**").hasRole(Roles.OPERATOR);
        if (enforced) {
            auth.requestMatchers("/api/**").authenticated();
        } else {
            auth.requestMatchers("/api/**").permitAll();
        }
        auth.anyRequest().denyAll();
    }

    @Configuration
    @ConditionalOnProperty(name = "audit.auth.enabled", havingValue = "true")
    static class Enforced {

        @Bean
        JwtDecoder cognitoJwtDecoder(AuthProperties props) {
            props.requireComplete();
            // Keys are fetched from the pool's JWKS endpoint on first use and
            // cached; an unknown kid triggers one refresh (key rotation).
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.resolvedJwkSetUri()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(props.issuerUri()),
                    new CognitoTokenValidator(props.clientId())));
            return decoder;
        }

        @Bean
        SecurityFilterChain enforcedChain(HttpSecurity http, AuthProperties props) throws Exception {
            log.info("Gateway auth ENFORCED: Cognito ID tokens from {} for client {}",
                    props.issuerUri(), props.clientId());
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(new CognitoGroupsConverter());
            return http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> authorize(auth, true))
                    .oauth2ResourceServer(rs -> rs
                            .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                            .authenticationEntryPoint(JsonAuthErrors.entryPoint())
                            .accessDeniedHandler(JsonAuthErrors.accessDeniedHandler()))
                    .exceptionHandling(e -> e
                            .authenticationEntryPoint(JsonAuthErrors.entryPoint())
                            .accessDeniedHandler(JsonAuthErrors.accessDeniedHandler()))
                    .build();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "audit.auth.enabled", havingValue = "false", matchIfMissing = true)
    static class Open {

        @Bean
        SecurityFilterChain openChain(HttpSecurity http) throws Exception {
            log.warn("Gateway auth DISABLED (audit.auth.enabled=false): every request is accepted and the "
                    + "customer is taken from the X-Customer-Id header. Local development only.");
            return http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .addFilterBefore(new DevHeaderAuthenticationFilter(), AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> authorize(auth, false))
                    .exceptionHandling(e -> e
                            .authenticationEntryPoint(JsonAuthErrors.entryPoint())
                            .accessDeniedHandler(JsonAuthErrors.accessDeniedHandler()))
                    .build();
        }
    }
}
