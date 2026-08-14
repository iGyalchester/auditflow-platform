package com.auditflow.enrichment.processors;

import com.auditflow.common.interfaces.EventProcessor;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps an event to the compliance controls it constitutes evidence for,
 * driven by the config in {@code shared/compliance-controls/*.yaml} via
 * {@link ComplianceControlsCatalog}.
 */
@Component
public class ControlClassifier implements EventProcessor {

    private final ComplianceControlsCatalog catalog;

    public ControlClassifier(ComplianceControlsCatalog catalog) {
        this.catalog = catalog;
    }

    public List<ComplianceControl> classify(AuditEvent event) {
        if (event.getType() == null) {
            return List.of();
        }
        return catalog.controlsFor(event.getType());
    }

    @Override
    public AuditEvent process(AuditEvent event) {
        return event.toBuilder().controls(classify(event)).build();
    }

    @Override
    public int order() {
        return 20;
    }
}
