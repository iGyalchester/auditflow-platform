package com.auditflow.ingestion.security;

import com.auditflow.common.ratelimit.RateLimitFilter;
import com.auditflow.common.ratelimit.RateLimitSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Ingestion's half of the shared rate limiter: a prefix, some defaults, and
 * where in the chain it sits. The filter itself lives in common-lib.
 *
 * <p>Sources here are few and chatty - the collector agent posts one
 * request per event - so the defaults are generous compared with the
 * gateway's. The point is not to ration normal traffic but to stop a
 * misbehaving or compromised source saturating Kafka for everyone else. A
 * limited request is a 429 with Retry-After, which the agent treats like
 * any non-2xx: hold the checkpoint and retry, so nothing is lost.
 */
@Configuration
@EnableConfigurationProperties(RateLimitConfig.IngestionRateLimitProperties.class)
public class RateLimitConfig {

    /**
     * Defaults are per source IP on {@code /api/**}: 200/s sustained with a
     * burst of 500, tracking at most 1,000 sources.
     *
     * <p>{@code clientIpHeader} is empty and stays empty here: nothing
     * proxies to ingestion, so the socket address is the source. Never
     * X-Forwarded-For.
     */
    @ConfigurationProperties("audit.ingestion.rate-limit")
    public record IngestionRateLimitProperties(
            boolean enabled,
            double requestsPerSecond,
            long burst,
            int maxTrackedSources,
            String clientIpHeader) {

        public IngestionRateLimitProperties {
            requestsPerSecond = requestsPerSecond > 0 ? requestsPerSecond : 200;
            burst = burst > 0 ? burst : 500;
            maxTrackedSources = maxTrackedSources > 0 ? maxTrackedSources : 1_000;
            clientIpHeader = clientIpHeader == null ? "" : clientIpHeader;
        }
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(IngestionRateLimitProperties props) {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitSettings(
                props.enabled(), props.requestsPerSecond(), props.burst(),
                props.maxTrackedSources(), props.clientIpHeader(), "/api/"));

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
