package com.auditflow.enrichment.processors;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControlClassifierTest {

    private final ControlClassifier classifier = new ControlClassifier();

    @Test
    void classifiesDataExportUnderSoc2AndGdpr() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-1")
                .customerId("cust-1")
                .type(EventType.DATA_EXPORT)
                .timestamp(Instant.now())
                .build();

        List<ComplianceControl> controls = classifier.classify(event);

        assertThat(controls)
                .extracting(ComplianceControl::getFramework)
                .contains("SOC2", "GDPR");
    }

    @Test
    void returnsNoControlsForUnclassifiedType() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-2")
                .customerId("cust-1")
                .type(EventType.AUTH_EVENT)
                .timestamp(Instant.now())
                .build();

        assertThat(classifier.classify(event))
                .extracting(ComplianceControl::getControlId)
                .contains("AC-2", "IA-2");
    }
}
