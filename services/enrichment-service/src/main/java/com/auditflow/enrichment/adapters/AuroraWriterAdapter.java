package com.auditflow.enrichment.adapters;

import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControls;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * Writes queryable audit event metadata to Aurora PostgreSQL, scoped by
 * customer_id per the multi-tenant-from-day-1 principle. The immutable
 * evidence itself lives in S3 ({@link S3WriterAdapter}); this sink is what
 * the reporting-service queries against for fast lookups.
 */
@Component
// Second: the queryable copy, so a report can resolve evidence that is
// already in S3. The insert is idempotent, so a retry is a no-op.
@Order(20)
public class AuroraWriterAdapter implements DataSink {

    private static final String INSERT_SQL = """
            INSERT INTO audit_events
                (event_id, customer_id, user_id, session_id, occurred_at, event_type, resource, action, risk_level, anomalous, controls)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuroraWriterAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(AuditEvent event) {
        jdbcTemplate.update(INSERT_SQL,
                event.getEventId(),
                event.getCustomerId(),
                event.getUserId(),
                event.getSessionId(),
                Timestamp.from(event.getTimestamp()),
                event.getType() != null ? event.getType().name() : null,
                event.getResource(),
                event.getAction(),
                event.getRiskLevel() != null ? event.getRiskLevel().name() : null,
                event.isAnomalous(),
                ComplianceControls.encode(event.getControls()));
    }

    @Override
    public void writeBatch(List<AuditEvent> events) {
        events.forEach(this::write);
    }

    @Override
    public String sinkName() {
        return "aurora";
    }
}
