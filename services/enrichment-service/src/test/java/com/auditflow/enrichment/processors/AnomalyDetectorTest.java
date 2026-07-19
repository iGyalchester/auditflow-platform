package com.auditflow.enrichment.processors;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {

    private final AnomalyDetector detector = new AnomalyDetector();

    @Test
    void flagsDataExportAsAnomalous() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-1")
                .customerId("cust-1")
                .type(EventType.DATA_EXPORT)
                .timestamp(Instant.now())
                .build();

        AuditEvent result = detector.process(event);

        assertThat(result.isAnomalous()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void doesNotFlagRoutineDatabaseQuery() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-2")
                .customerId("cust-1")
                .type(EventType.DATABASE_QUERY)
                .timestamp(Instant.now())
                .build();

        AuditEvent result = detector.process(event);

        assertThat(result.isAnomalous()).isFalse();
    }
}
