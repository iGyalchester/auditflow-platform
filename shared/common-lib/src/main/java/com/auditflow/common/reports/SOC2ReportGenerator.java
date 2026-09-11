package com.auditflow.common.reports;

import com.auditflow.common.interfaces.ReportGenerator;
import com.auditflow.common.model.AuditEvent;
import com.auditflow.common.model.ComplianceControl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public class SOC2ReportGenerator implements ReportGenerator {

    @Override
    public byte[] generate(String customerId, Instant from, Instant to, List<AuditEvent> events) {
        StringBuilder report = new StringBuilder();
        report.append("SOC 2 Evidence Report\n");
        report.append("Customer: ").append(customerId).append('\n');
        report.append("Period: ").append(from).append(" - ").append(to).append('\n');

        List<AuditEvent> relevant = events.stream()
                .filter(event -> event.getControls().stream()
                        .anyMatch(control -> framework().equals(control.getFramework())))
                .toList();

        report.append("Events: ").append(relevant.size()).append('\n');
        for (AuditEvent event : relevant) {
            String controlIds = event.getControls().stream()
                    .filter(c -> framework().equals(c.getFramework()))
                    .map(ComplianceControl::getControlId)
                    .distinct()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            report.append(event.getEventId()).append(" | ").append(event.getType())
                    .append(" | controls=").append(controlIds)
                    .append(" | risk=").append(event.getRiskLevel()).append('\n');
        }
        return report.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String framework() {
        return "SOC2";
    }
}
