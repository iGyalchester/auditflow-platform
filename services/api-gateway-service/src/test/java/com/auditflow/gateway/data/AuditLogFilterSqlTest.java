package com.auditflow.gateway.data;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The SQL the explorer's filters produce; the real query runs in RepositoriesIntegrationTest. */
class AuditLogFilterSqlTest {

    @Test
    void noFilterAddsNothing() {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        AuditLogRepository.appendFilter(sql, args, AuditLogFilter.NONE);
        assertThat(sql).isEmpty();
        assertThat(args).isEmpty();
    }

    @Test
    void everyFilterIsAParameterNeverInterpolated() {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        AuditLogRepository.appendFilter(sql, args,
                new AuditLogFilter("AUTH_EVENT", "HIGH", "boris", true, "50%_off\\", from, null));

        assertThat(sql.toString()).isEqualTo(" AND event_type = ? AND risk_level = ? AND user_id = ?"
                + " AND anomalous = ? AND (resource ILIKE ? OR action ILIKE ?) AND occurred_at >= ?");
        // the search text's wildcards are escaped so they match literally
        assertThat(args).containsExactly("AUTH_EVENT", "HIGH", "boris", true,
                "%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%", java.sql.Timestamp.from(from));
        assertThat(sql.toString()).doesNotContain("boris");
    }

    @Test
    void theDryRunsCheapCriteriaBecomeSqlAndRiskIsAtOrAbove() {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        AuditLogRepository.appendCriteria(sql, args, EventType.AUTH_EVENT, RiskLevel.HIGH);
        assertThat(sql.toString()).isEqualTo(" AND event_type = ? AND risk_level IN (?, ?)");
        assertThat(args).containsExactly("AUTH_EVENT", "HIGH", "CRITICAL");

        sql = new StringBuilder();
        args = new ArrayList<>();
        AuditLogRepository.appendCriteria(sql, args, null, RiskLevel.LOW);
        assertThat(sql.toString()).isEqualTo(" AND risk_level IN (?, ?, ?, ?)");
        assertThat(args).containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");

        sql = new StringBuilder();
        args = new ArrayList<>();
        AuditLogRepository.appendCriteria(sql, args, null, null);
        assertThat(sql).isEmpty();
        assertThat(args).isEmpty();
    }
}
