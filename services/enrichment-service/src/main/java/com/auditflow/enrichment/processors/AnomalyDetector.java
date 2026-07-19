package com.auditflow.enrichment.processors;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.interfaces.EventProcessor;
import com.auditflow.common.model.AuditEvent;
import org.springframework.stereotype.Component;

/**
 * First-pass anomaly heuristic. Flags high-risk data exports and permission
 * changes; the roadmap calls for this to grow into a statistical/ML model
 * over per-user baselines.
 */
@Component
public class AnomalyDetector implements EventProcessor {

    @Override
    public AuditEvent process(AuditEvent event) {
        boolean anomalous = isAnomalous(event);
        RiskLevel riskLevel = anomalous ? RiskLevel.HIGH : event.getRiskLevel();
        return event.toBuilder().anomalous(anomalous).riskLevel(riskLevel).build();
    }

    private boolean isAnomalous(AuditEvent event) {
        if (event.getType() == EventType.DATA_EXPORT) {
            return true;
        }
        return event.getType() == EventType.PERMISSION_CHANGE
                && event.getRiskLevel() == RiskLevel.CRITICAL;
    }

    @Override
    public int order() {
        return 30;
    }
}
