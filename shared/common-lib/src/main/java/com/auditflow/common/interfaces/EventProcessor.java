package com.auditflow.common.interfaces;

import com.auditflow.common.model.AuditEvent;

/**
 * A single stage in the enrichment pipeline (e.g. user-context enrichment,
 * control classification, anomaly detection). Processors are composable and
 * should be side-effect free with respect to their input.
 */
public interface EventProcessor {

    AuditEvent process(AuditEvent event);

    default int order() {
        return 0;
    }
}
