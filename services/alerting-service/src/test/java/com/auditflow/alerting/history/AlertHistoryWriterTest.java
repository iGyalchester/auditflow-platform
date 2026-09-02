package com.auditflow.alerting.history;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AlertHistoryWriterTest {

    @Test
    void insertsOneRowWithTheChannelsThatGotThrough() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AlertHistoryWriter writer = new AlertHistoryWriter(jdbc);
        AlertRule rule = AlertRule.builder().ruleId("r1").customerId("acme").build();
        AuditEvent event = AuditEvent.builder().eventId("evt-1").customerId("acme")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();

        String alertId = writer.record(rule, event, List.of("slack", "email"));

        assertThat(alertId).isNotBlank();
        verify(jdbc).update(eq(AlertHistoryWriter.INSERT_SQL), eq(alertId), eq("r1"), eq("evt-1"), eq("acme"), eq("slack,email"));
    }

    @Test
    void zeroChannelsIsStillAnAlert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AlertHistoryWriter writer = new AlertHistoryWriter(jdbc);
        AlertRule rule = AlertRule.builder().ruleId("r1").customerId("acme").build();
        AuditEvent event = AuditEvent.builder().eventId("evt-1").customerId("acme")
                .type(EventType.AUTH_EVENT).timestamp(Instant.now()).build();

        writer.record(rule, event, List.of());

        verify(jdbc).update(eq(AlertHistoryWriter.INSERT_SQL), anyString(), eq("r1"), eq("evt-1"), eq("acme"), eq(""));
    }
}
