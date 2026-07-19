package com.auditflow.common.interfaces;

import com.auditflow.common.model.AuditEvent;

import java.util.List;

/**
 * Collects raw activity from a source system (a database's query log, an
 * API gateway, etc.) and normalizes it into {@link AuditEvent}s.
 */
public interface EventCollector {

    List<AuditEvent> collect();

    String sourceName();
}
