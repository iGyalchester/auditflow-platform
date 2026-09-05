package com.auditflow.common.reports;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SOC2ReportGeneratorTest {

    private final SOC2ReportGenerator generator = new SOC2ReportGenerator();

    @Test
    void includesOnlySoc2TaggedEvents() {
        AuditEvent soc2Event = AuditEvent.builder()
                .eventId("evt-soc2")
                .customerId("cust-1")
                .type(EventType.DATABASE_QUERY)
                .timestamp(Instant.now())
                .controls(List.of(ComplianceControl.builder().controlId("AC-2").framework("SOC2").build()))
                .build();
        AuditEvent gdprOnlyEvent = AuditEvent.builder()
                .eventId("evt-gdpr")
                .customerId("cust-1")
                .type(EventType.DATA_EXPORT)
                .timestamp(Instant.now())
                .controls(List.of(ComplianceControl.builder().controlId("Art-30").framework("GDPR").build()))
                .build();

        byte[] report = generator.generate("cust-1", Instant.now(), Instant.now(), List.of(soc2Event, gdprOnlyEvent));
        String text = new String(report, StandardCharsets.UTF_8);

        assertThat(text).contains("evt-soc2").doesNotContain("evt-gdpr");
    }
}
