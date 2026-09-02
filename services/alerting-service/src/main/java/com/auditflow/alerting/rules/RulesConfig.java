package com.auditflow.alerting.rules;

import com.auditflow.common.rules.ConditionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RulesConfig {

    @Bean
    public ConditionEvaluator conditionEvaluator() {
        return new ConditionEvaluator();
    }
}
