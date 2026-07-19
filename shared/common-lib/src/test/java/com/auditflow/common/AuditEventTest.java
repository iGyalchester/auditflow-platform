package com.auditflow.common;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditEventTest {

    @Test
    void buildsWithRequiredFields() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-1")
                .customerId("cust-1")
                .type(EventType.DATABASE_QUERY)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .riskLevel(RiskLevel.HIGH)
                .build();

        assertEquals("evt-1", event.getEventId());
        assertEquals(RiskLevel.HIGH, event.getRiskLevel());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(NullPointerException.class, () -> AuditEvent.builder().eventId("evt-1").build());
    }
}
