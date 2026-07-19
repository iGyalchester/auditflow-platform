package com.auditflow.common.model;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;
import java.util.Objects;

/**
 * A customer-defined rule evaluated by the alerting-service RuleEngine.
 */
@JsonDeserialize(builder = AlertRule.Builder.class)
public final class AlertRule {

    private final String ruleId;
    private final String customerId;
    private final String name;
    private final String description;
    private final EventType eventType;
    private final RiskLevel riskThreshold;
    private final String conditionExpression;
    private final boolean enabled;
    private final List<String> notificationChannels;

    private AlertRule(Builder builder) {
        this.ruleId = builder.ruleId;
        this.customerId = builder.customerId;
        this.name = builder.name;
        this.description = builder.description;
        this.eventType = builder.eventType;
        this.riskThreshold = builder.riskThreshold;
        this.conditionExpression = builder.conditionExpression;
        this.enabled = builder.enabled;
        this.notificationChannels = builder.notificationChannels;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EventType getEventType() {
        return eventType;
    }

    public RiskLevel getRiskThreshold() {
        return riskThreshold;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getNotificationChannels() {
        return notificationChannels;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlertRule that)) return false;
        return Objects.equals(ruleId, that.ruleId) && Objects.equals(customerId, that.customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleId, customerId);
    }

    @Override
    public String toString() {
        return "AlertRule{ruleId='%s', customerId='%s', name='%s', enabled=%s}"
                .formatted(ruleId, customerId, name, enabled);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String ruleId;
        private String customerId;
        private String name;
        private String description;
        private EventType eventType;
        private RiskLevel riskThreshold;
        private String conditionExpression;
        private boolean enabled = true;
        private List<String> notificationChannels = List.of();

        public Builder ruleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder eventType(EventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder riskThreshold(RiskLevel riskThreshold) {
            this.riskThreshold = riskThreshold;
            return this;
        }

        public Builder conditionExpression(String conditionExpression) {
            this.conditionExpression = conditionExpression;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder notificationChannels(List<String> notificationChannels) {
            this.notificationChannels = notificationChannels;
            return this;
        }

        public AlertRule build() {
            return new AlertRule(this);
        }
    }
}
