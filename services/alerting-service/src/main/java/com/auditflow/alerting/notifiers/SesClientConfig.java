package com.auditflow.alerting.notifiers;

import com.auditflow.alerting.AlertingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * An SES client only when a sender is configured; credentials come from
 * the default provider chain (the ECS task role in AWS).
 */
@Configuration
public class SesClientConfig {

    @Bean
    @ConditionalOnExpression("!'${audit.alerting.email.from:}'.isBlank()")
    public SesClient sesClient(AlertingProperties props) {
        String region = props.email().region();
        return SesClient.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .build();
    }
}
