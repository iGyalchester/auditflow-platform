package com.auditflow.common.interfaces;

import com.auditflow.common.model.AuditEvent;

import java.time.Instant;
import java.util.List;

/**
 * Produces a compliance report (SOC 2, GDPR, HIPAA, ...) for a customer over
 * a time window, from the set of audit events relevant to that framework.
 */
public interface ReportGenerator {

    byte[] generate(String customerId, Instant from, Instant to, List<AuditEvent> events);

    String framework();
}
