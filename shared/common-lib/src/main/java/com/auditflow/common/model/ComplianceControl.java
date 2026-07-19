package com.auditflow.common.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;
import java.util.Objects;

/**
 * A config-driven compliance control definition (e.g. SOC 2 "AC-2", "AU-2").
 */
@JsonDeserialize(builder = ComplianceControl.Builder.class)
public final class ComplianceControl {

    private final String controlId;
    private final String framework;
    private final String name;
    private final String description;
    private final List<String> evidenceTypes;
    private final List<String> queryPatterns;

    private ComplianceControl(Builder builder) {
        this.controlId = builder.controlId;
        this.framework = builder.framework;
        this.name = builder.name;
        this.description = builder.description;
        this.evidenceTypes = builder.evidenceTypes;
        this.queryPatterns = builder.queryPatterns;
    }

    public String getControlId() {
        return controlId;
    }

    public String getFramework() {
        return framework;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getEvidenceTypes() {
        return evidenceTypes;
    }

    public List<String> getQueryPatterns() {
        return queryPatterns;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ComplianceControl that)) return false;
        return Objects.equals(controlId, that.controlId) && Objects.equals(framework, that.framework);
    }

    @Override
    public int hashCode() {
        return Objects.hash(controlId, framework);
    }

    @Override
    public String toString() {
        return "ComplianceControl{controlId='%s', framework='%s', name='%s'}".formatted(controlId, framework, name);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String controlId;
        private String framework;
        private String name;
        private String description;
        private List<String> evidenceTypes = List.of();
        private List<String> queryPatterns = List.of();

        public Builder controlId(String controlId) {
            this.controlId = controlId;
            return this;
        }

        public Builder framework(String framework) {
            this.framework = framework;
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

        public Builder evidenceTypes(List<String> evidenceTypes) {
            this.evidenceTypes = evidenceTypes;
            return this;
        }

        public Builder queryPatterns(List<String> queryPatterns) {
            this.queryPatterns = queryPatterns;
            return this;
        }

        public ComplianceControl build() {
            return new ComplianceControl(this);
        }
    }
}
