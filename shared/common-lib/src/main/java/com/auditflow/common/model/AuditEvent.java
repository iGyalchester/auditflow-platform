package com.auditflow.common.model;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical, immutable record of a single audit-relevant action, as it
 * flows through ingestion -> enrichment -> alerting -> reporting.
 */
@JsonDeserialize(builder = AuditEvent.Builder.class)
public final class AuditEvent {

    private final String eventId;
    private final String customerId;
    private final String userId;
    private final String sessionId;
    private final Instant timestamp;
    private final EventType type;
    private final String resource;
    private final String action;
    private final String query;
    private final String ipAddress;
    private final String userAgent;
    private final String location;
    private final List<ComplianceControl> controls;
    private final RiskLevel riskLevel;
    private final boolean anomalous;
    private final Map<String, String> tags;
    private final String rawLog;

    private AuditEvent(Builder builder) {
        this.eventId = builder.eventId;
        this.customerId = builder.customerId;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.timestamp = builder.timestamp;
        this.type = builder.type;
        this.resource = builder.resource;
        this.action = builder.action;
        this.query = builder.query;
        this.ipAddress = builder.ipAddress;
        this.userAgent = builder.userAgent;
        this.location = builder.location;
        this.controls = builder.controls;
        this.riskLevel = builder.riskLevel;
        this.anomalous = builder.anomalous;
        this.tags = builder.tags;
        this.rawLog = builder.rawLog;
    }

    public String getEventId() {
        return eventId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public EventType getType() {
        return type;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }

    public String getQuery() {
        return query;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getLocation() {
        return location;
    }

    public List<ComplianceControl> getControls() {
        return controls;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public boolean isAnomalous() {
        return anomalous;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public String getRawLog() {
        return rawLog;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .eventId(eventId)
                .customerId(customerId)
                .userId(userId)
                .sessionId(sessionId)
                .timestamp(timestamp)
                .type(type)
                .resource(resource)
                .action(action)
                .query(query)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .location(location)
                .controls(controls)
                .riskLevel(riskLevel)
                .anomalous(anomalous)
                .tags(tags)
                .rawLog(rawLog);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditEvent that)) return false;
        return Objects.equals(eventId, that.eventId) && Objects.equals(customerId, that.customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, customerId);
    }

    @Override
    public String toString() {
        return "AuditEvent{eventId='%s', customerId='%s', type=%s, riskLevel=%s}"
                .formatted(eventId, customerId, type, riskLevel);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String eventId;
        private String customerId;
        private String userId;
        private String sessionId;
        private Instant timestamp;
        private EventType type;
        private String resource;
        private String action;
        private String query;
        private String ipAddress;
        private String userAgent;
        private String location;
        private List<ComplianceControl> controls = List.of();
        private RiskLevel riskLevel = RiskLevel.LOW;
        private boolean anomalous = false;
        private Map<String, String> tags = Map.of();
        private String rawLog;

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder type(EventType type) {
            this.type = type;
            return this;
        }

        public Builder resource(String resource) {
            this.resource = resource;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder controls(List<ComplianceControl> controls) {
            this.controls = controls;
            return this;
        }

        public Builder riskLevel(RiskLevel riskLevel) {
            this.riskLevel = riskLevel;
            return this;
        }

        public Builder anomalous(boolean anomalous) {
            this.anomalous = anomalous;
            return this;
        }

        public Builder tags(Map<String, String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder rawLog(String rawLog) {
            this.rawLog = rawLog;
            return this;
        }

        public AuditEvent build() {
            Objects.requireNonNull(eventId, "eventId is required");
            Objects.requireNonNull(customerId, "customerId is required");
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(timestamp, "timestamp is required");
            return new AuditEvent(this);
        }
    }
}
