package com.auditflow.gateway.api;

import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;

import java.util.List;

/** One event and the alerts it raised, newest alert first. */
public record AuditLogDetail(AuditLogRow event, List<AlertRow> alerts) {
}
