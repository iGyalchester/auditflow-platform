package com.auditflow.common.rules;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.List;

/**
 * How an {@link AlertRule} is stored in and read back from the alert_rules
 * table.
 *
 * <p>Three places used to know this independently: alerting's
 * {@code JdbcRuleRepository} mapping rows in, the gateway's
 * {@code AlertRuleRepository} mapping rows in *and* binding them out, and
 * {@code RuleSeeder} binding nine parameters by hand. The column list, the
 * channel encoding and the parameter order all had to agree across the
 * three, with nothing to make them.
 *
 * <p>The lenient enum handling is the part that most wanted one home. A
 * stored {@code event_type} the enum does not know - easy to produce by
 * hand or by an older writer - used to throw out of the row mapper and
 * abort the whole query, emptying the rule set. One bad row silently
 * disarmed every rule. Here it yields a null row with a WARN naming it, and
 * callers drop nulls.
 */
public final class AlertRuleRows {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleRows.class);

    private AlertRuleRows() {
    }

    /** Every column, in the order {@link #insertParams} binds them. */
    public static final String COLUMNS = """
            rule_id, customer_id, name, description, event_type, risk_threshold,
            condition_expression, enabled, notification_channels""";

    /** Returns null for a row that cannot be read; callers drop those. */
    public static final RowMapper<AlertRule> MAPPER = (rs, i) -> {
        String ruleId = rs.getString("rule_id");
        String rawEventType = rs.getString("event_type");
        String rawRiskThreshold = rs.getString("risk_threshold");
        EventType eventType = parseEnum(EventType.class, rawEventType, ruleId, "event_type");
        RiskLevel riskThreshold = parseEnum(RiskLevel.class, rawRiskThreshold, ruleId, "risk_threshold");
        if ((rawEventType != null && eventType == null)
                || (rawRiskThreshold != null && riskThreshold == null)) {
            return null;
        }
        return AlertRule.builder()
                .ruleId(ruleId)
                .customerId(rs.getString("customer_id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .eventType(eventType)
                .riskThreshold(riskThreshold)
                .conditionExpression(rs.getString("condition_expression"))
                .enabled(rs.getBoolean("enabled"))
                .notificationChannels(splitChannels(rs.getString("notification_channels")))
                .build();
    };

    /**
     * The nine values for {@link #COLUMNS}, in that order. Both writers use
     * this, so an added column is one edit rather than three that have to
     * agree.
     */
    public static Object[] insertParams(AlertRule rule) {
        return new Object[]{
                rule.getRuleId(),
                rule.getCustomerId(),
                rule.getName(),
                rule.getDescription(),
                rule.getEventType() != null ? rule.getEventType().name() : null,
                rule.getRiskThreshold() != null ? rule.getRiskThreshold().name() : null,
                rule.getConditionExpression(),
                rule.isEnabled(),
                joinChannels(rule.getNotificationChannels())
        };
    }

    /** "slack,email" - the column is a VARCHAR(255), not an array. */
    public static String joinChannels(List<String> channels) {
        return channels == null ? "" : String.join(",", channels);
    }

    public static List<String> splitChannels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
    }

    /** @return the constant, or null (with a WARN) when the stored value is not one */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String ruleId, String column) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            log.warn("Alert rule '{}' has an unknown {} '{}'; skipping that rule", ruleId, column, value);
            return null;
        }
    }
}
