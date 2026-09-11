package com.auditflow.gateway.api;

import com.auditflow.gateway.data.AlertHistoryRepository.AlertRow;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;

import java.util.List;

/**
 * One alert, the event that raised it (null when the event has since been
 * purged), and the delivery picture: what the rule asks for today, what
 * was actually reached when it fired, and the difference.
 */
public record AlertDetail(AlertRow alert, AuditLogRow event, List<String> configuredChannels,
                          List<String> notifiedChannels, List<String> undeliveredChannels) {
}
