package com.auditflow.agent.collector;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MySqlGeneralLogCollectorTest {

    private final MySqlGeneralLogCollector collector = new MySqlGeneralLogCollector(
            mock(org.springframework.jdbc.core.JdbcTemplate.class),
            "resistance", "resistance-mysql", 500);

    private final Instant now = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void mapsALogRowToARedactedEvent() {
        AuditEvent event = collector.toEvent(now, "springstudent[springstudent] @ localhost [127.0.0.1]",
                42L, "SELECT * FROM job_application WHERE owner_account_id = 7");

        assertThat(event.getType()).isEqualTo(EventType.DATABASE_QUERY);
        assertThat(event.getCustomerId()).isEqualTo("resistance");
        assertThat(event.getUserId()).isEqualTo("springstudent");
        assertThat(event.getResource()).isEqualTo("resistance-mysql");
        assertThat(event.getAction()).isEqualTo("SELECT");
        assertThat(event.getQuery()).isEqualTo("SELECT * FROM job_application WHERE owner_account_id = ?");
        assertThat(event.getRawLog()).isNull();
        assertThat(event.getEventId()).startsWith("mysql-");
    }

    @Test
    void eventIdIsDeterministicForTheSameRow() {
        String statement = "SELECT 1";
        AuditEvent first = collector.toEvent(now, "u[u] @ h []", 1L, statement);
        AuditEvent second = collector.toEvent(now, "u[u] @ h []", 1L, statement);
        AuditEvent different = collector.toEvent(now, "u[u] @ h []", 2L, statement);

        assertThat(first.getEventId()).isEqualTo(second.getEventId());
        assertThat(first.getEventId()).isNotEqualTo(different.getEventId());
    }

    @Test
    void filtersSelfReferentialAndSystemNoise() {
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L,
                "SELECT MAX(event_time) FROM mysql.general_log")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L,
                "SELECT * FROM information_schema.tables")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "SET autocommit=1")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "SHOW VARIABLES")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "select @@session.tx_isolation")).isNull();
        assertThat(collector.toEvent(now, "u[u] @ h []", 1L, "")).isNull();
    }

    @Test
    void unparseableUserHostFallsBackSafely() {
        assertThat(collector.toEvent(now, null, 1L, "SELECT 1").getUserId()).isEqualTo("unknown");
        assertThat(collector.toEvent(now, "plainuser", 1L, "SELECT 1").getUserId()).isEqualTo("plainuser");
    }
}
