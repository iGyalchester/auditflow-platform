package com.auditflow.gateway.security;

import com.auditflow.common.ratelimit.RateLimitFilter;
import com.auditflow.common.ratelimit.RateLimitSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The gateway's half of the shared rate limiter: a prefix, some defaults,
 * and where in the chain it sits. The filter itself lives in common-lib.
 *
 * <p>Registered ahead of everything else so a brute-force or a runaway
 * client is refused before it costs a JWKS lookup or a database query.
 */
@Configuration
@EnableConfigurationProperties(RateLimitConfig.GatewayRateLimitProperties.class)
public class RateLimitConfig {

    /**
     * Defaults are per client IP on {@code /api/**}: 20/s sustained with a
     * burst of 40, tracking at most 10,000 clients.
     *
     * @param clientIpHeader header a trusted proxy sets to the real client
     *        address. The {@code aws} profile sets X-Client-IP, which the
     *        API Gateway integration overwrites from
     *        {@code $context.identity.sourceIp}. Never X-Forwarded-For.
     */
    @ConfigurationProperties("audit.rate-limit")
    public record GatewayRateLimitProperties(
            boolean enabled,
            double requestsPerSecond,
            long burst,
            int maxTrackedClients,
            String clientIpHeader) {

        public GatewayRateLimitProperties {
            requestsPerSecond = requestsPerSecond > 0 ? requestsPerSecond : 20;
            burst = burst > 0 ? burst : 40;
            maxTrackedClients = maxTrackedClients > 0 ? maxTrackedClients : 10_000;
            clientIpHeader = clientIpHeader == null ? "" : clientIpHeader;
        }
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(GatewayRateLimitProperties props) {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitSettings(
                props.enabled(), props.requestsPerSecond(), props.burst(),
                props.maxTrackedClients(), props.clientIpHeader(), "/api/"));

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
