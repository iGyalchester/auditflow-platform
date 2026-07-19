package com.auditflow.enrichment.processors;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.interfaces.EventProcessor;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Maps an event to the compliance controls it constitutes evidence for.
 * This is a first-pass, hard-coded lookup; the roadmap calls for it to
 * become config-driven from {@code shared/compliance-controls/*.yaml}.
 */
@Component
public class ControlClassifier implements EventProcessor {

    private static final Map<EventType, List<ComplianceControl>> CONTROLS_BY_EVENT_TYPE = Map.of(
            EventType.DATABASE_QUERY, List.of(
                    control("AC-2", "SOC2", "Account Management")),
            EventType.API_CALL, List.of(
                    control("AC-2", "SOC2", "Account Management")),
            EventType.AUTH_EVENT, List.of(
                    control("AC-2", "SOC2", "Account Management"),
                    control("IA-2", "SOC2", "Identification and Authentication")),
            EventType.FILE_ACCESS, List.of(
                    control("AU-2", "SOC2", "Audit Events")),
            EventType.PERMISSION_CHANGE, List.of(
                    control("AC-6", "SOC2", "Least Privilege")),
            EventType.DATA_EXPORT, List.of(
                    control("AU-2", "SOC2", "Audit Events"),
                    control("Art-30", "GDPR", "Records of Processing Activities"))
    );

    public List<ComplianceControl> classify(AuditEvent event) {
        if (event.getType() == null) {
            return List.of();
        }
        return CONTROLS_BY_EVENT_TYPE.getOrDefault(event.getType(), List.of());
    }

    @Override
    public AuditEvent process(AuditEvent event) {
        return event.toBuilder().controls(classify(event)).build();
    }

    @Override
    public int order() {
        return 20;
    }

    private static ComplianceControl control(String controlId, String framework, String name) {
        return ComplianceControl.builder()
                .controlId(controlId)
                .framework(framework)
                .name(name)
                .build();
    }
}
