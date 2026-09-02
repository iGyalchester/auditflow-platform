package com.auditflow.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The compact form controls take in the audit_events.controls column:
 * {@code FRAMEWORK:CONTROL_ID} pairs, comma-separated - e.g.
 * {@code SOC2:AC-2,SOC2:IA-2,GDPR:Art-30}. Only what the reports need
 * (framework + id) round-trips; names and descriptions live in the
 * control catalogue, not on every event row.
 */
public final class ComplianceControls {

    private ComplianceControls() {
    }

    public static String encode(List<ComplianceControl> controls) {
        if (controls == null || controls.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (ComplianceControl control : controls) {
            if (control.getFramework() == null || control.getControlId() == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(control.getFramework()).append(':').append(control.getControlId());
        }
        return out.length() == 0 ? null : out.toString();
    }

    public static List<ComplianceControl> decode(String encoded) {
        List<ComplianceControl> controls = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return controls;
        }
        for (String pair : encoded.split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0 || colon == pair.length() - 1) {
                continue;
            }
            controls.add(ComplianceControl.builder()
                    .framework(pair.substring(0, colon).trim())
                    .controlId(pair.substring(colon + 1).trim())
                    .build());
        }
        return controls;
    }
}
