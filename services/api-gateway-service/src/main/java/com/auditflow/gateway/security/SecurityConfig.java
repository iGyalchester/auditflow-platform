package com.auditflow.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource-server security for the gateway. Two mutually
 * exclusive modes selected by {@code audit.auth.enabled} - see
 * {@link AuthProperties}. Session cookies and CSRF are off in both: every
 * caller is an API client presenting a bearer token, not a browser form.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

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
            return http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().denyAll())
                    .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
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
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }
}
