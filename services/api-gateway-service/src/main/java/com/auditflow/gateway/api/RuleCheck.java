package com.auditflow.gateway.api;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.gateway.data.AuditLogRepository.AuditLogRow;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** The rule editor's two questions: "is this valid?" and "how often would it fire?". */
public final class RuleCheck {

    private RuleCheck() {
    }

    /** The parts of a rule that decide whether an event matches. */
    public record Draft(EventType eventType, RiskLevel riskThreshold,
                        @Size(max = ConditionEvaluator.MAX_EXPRESSION_LENGTH) String conditionExpression) {
    }

    /**
     * @param error the evaluator's reason when invalid, otherwise null
     */
    public record Validation(boolean valid, String error) {
    }

    /**
     * @param scanned how many of the customer's events in the window were evaluated
     * @param matched how many the draft would have fired on
     * @param sample  up to five of the matches, oldest first
     */
    public record DryRun(Instant from, Instant to, int scanned, int matched, List<AuditLogRow> sample) {
    }
}
