package com.auditflow.gateway.data;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.rules.AlertRuleRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A customer's alert rules in alert_rules. Every statement carries the
 * customer id, so a rule id from another tenant is simply "not found".
 */
@Repository
public class AlertRuleRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlertRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertRule> findAll(String customerId) {
        return jdbcTemplate.query(
                        "SELECT " + AlertRuleRows.COLUMNS + " FROM alert_rules WHERE customer_id = ? ORDER BY name",
                        AlertRuleRows.MAPPER, customerId).stream()
                // MAPPER returns null for a row whose stored enum is unknown;
                // one such row must not take out the customer's whole list
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<AlertRule> find(String customerId, String ruleId) {
        return jdbcTemplate.query(
                        "SELECT " + AlertRuleRows.COLUMNS + " FROM alert_rules WHERE customer_id = ? AND rule_id = ?",
                        AlertRuleRows.MAPPER, customerId, ruleId).stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * Insert or replace; the rule's own customerId is the scope.
     *
     * @return the number of rows written - 0 when the rule id exists but
     *         belongs to another customer, because the WHERE clause below
     *         makes that a no-op rather than a hijack. Callers turn 0 into
     *         a 404 rather than issuing a SELECT first.
     */
    private static final String UPSERT_SQL =
            "INSERT INTO alert_rules (" + AlertRuleRows.COLUMNS + ") "
                    + """
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (rule_id) DO UPDATE SET
                        name = EXCLUDED.name, description = EXCLUDED.description,
                        event_type = EXCLUDED.event_type, risk_threshold = EXCLUDED.risk_threshold,
                        condition_expression = EXCLUDED.condition_expression, enabled = EXCLUDED.enabled,
                        notification_channels = EXCLUDED.notification_channels
                    WHERE alert_rules.customer_id = EXCLUDED.customer_id
                    """;

    public int upsert(AlertRule rule) {
        return jdbcTemplate.update(UPSERT_SQL, AlertRuleRows.insertParams(rule));
    }

    /**
     * Replaces an existing rule of this customer.
     *
     * <p>Deliberately an UPDATE and not the upsert above: PUT must not
     * create. Ids are server-generated on POST, and an upsert here would let
     * a client pick its own id in a globally unique namespace and quietly
     * turn a typo'd path into a new rule.
     *
     * <p>One statement rather than a SELECT then a write, so there is no
     * window in which the rule is deleted between the check and the update.
     * The customer_id predicate is what makes another tenant's rule id
     * count zero rather than being overwritten.
     *
     * @return the number of rows changed: 0 means no such rule for this
     *         customer, whether it does not exist or belongs to someone else
     */
    public int update(AlertRule rule) {
        return jdbcTemplate.update("""
                UPDATE alert_rules SET
                    name = ?, description = ?, event_type = ?, risk_threshold = ?,
                    condition_expression = ?, enabled = ?, notification_channels = ?
                WHERE rule_id = ? AND customer_id = ?
                """,
                rule.getName(), rule.getDescription(),
                rule.getEventType() != null ? rule.getEventType().name() : null,
                rule.getRiskThreshold() != null ? rule.getRiskThreshold().name() : null,
                rule.getConditionExpression(), rule.isEnabled(),
                AlertRuleRows.joinChannels(rule.getNotificationChannels()),
                rule.getRuleId(), rule.getCustomerId());
    }

    /** @return true when a row of this customer was deleted */
    public boolean delete(String customerId, String ruleId) {
        return jdbcTemplate.update("DELETE FROM alert_rules WHERE customer_id = ? AND rule_id = ?", customerId, ruleId) == 1;
    }

}
