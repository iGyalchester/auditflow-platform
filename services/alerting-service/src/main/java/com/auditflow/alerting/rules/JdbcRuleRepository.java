package com.auditflow.alerting.rules;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The rules alerting actually matches against: the alert_rules table, read
 * in full into memory and refreshed on a timer. A rule created or changed
 * through the gateway's API is live within one refresh interval (30 s by
 * default) - no restart, no redeploy.
 *
 * <p>Two failure modes are handled deliberately differently.
 *
 * <p><b>A later refresh that fails</b> keeps the last good set: a database
 * blip should not silently disarm every rule. But the <b>first</b> load
 * failing used to do exactly that - the service started with an empty map
 * and matched nothing, looking perfectly healthy while every alert was
 * missed. That now throws out of {@code @PostConstruct}, so the service
 * refuses to start rather than run blind.
 *
 * <p><b>One unreadable row</b> - an event_type or risk_threshold the enum
 * does not know, which is easy to produce by hand or by an older writer -
 * used to throw out of the row mapper and abort the whole query, emptying
 * the rule set. Such a row is now skipped with a WARN naming it, and every
 * other rule keeps working.
 */
@Component
public class JdbcRuleRepository implements RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleRepository.class);

    static final String SELECT_SQL = """
            SELECT rule_id, customer_id, name, description, event_type, risk_threshold,
                   condition_expression, enabled, notification_channels
            FROM alert_rules
            """;

    /** Returns null for a row that cannot be read; refresh() drops those. */
    static final RowMapper<AlertRule> ROW_MAPPER = (rs, i) -> {
        String ruleId = rs.getString("rule_id");
        EventType eventType = parseEnum(EventType.class, rs.getString("event_type"), ruleId, "event_type");
        RiskLevel riskThreshold = parseEnum(RiskLevel.class, rs.getString("risk_threshold"), ruleId, "risk_threshold");
        if ((rs.getString("event_type") != null && eventType == null)
                || (rs.getString("risk_threshold") != null && riskThreshold == null)) {
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
                .notificationChannels(channels(rs.getString("notification_channels")))
                .build();
    };

    private final JdbcTemplate jdbcTemplate;
    private volatile Map<String, List<AlertRule>> byCustomer = Map.of();
    private volatile boolean loadedOnce;

    /** The seeder is a constructor dependency only to guarantee it runs first. */
    public JdbcRuleRepository(JdbcTemplate jdbcTemplate, RuleSeeder seeder) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${audit.alerting.rules-refresh:PT30S}", initialDelayString = "${audit.alerting.rules-refresh:PT30S}")
    public void refresh() {
        try {
            List<AlertRule> rules = jdbcTemplate.query(SELECT_SQL, ROW_MAPPER).stream()
                    .filter(Objects::nonNull)
                    .toList();
            byCustomer = rules.stream().collect(Collectors.groupingBy(AlertRule::getCustomerId));
            loadedOnce = true;
            log.debug("Loaded {} alert rules for {} customers", rules.size(), byCustomer.size());
        } catch (Exception e) {
            if (!loadedOnce) {
                // starting with no rules means matching nothing, silently -
                // better to fail the startup than to look healthy and miss
                // every alert
                throw new IllegalStateException(
                        "Could not load alert rules on startup; refusing to start with no rules", e);
            }
            log.warn("Could not refresh alert rules, keeping the previous set: {}", e.toString());
        }
    }

    @Override
    public List<AlertRule> rulesFor(String customerId) {
        return byCustomer.getOrDefault(customerId, List.of());
    }

    static List<String> channels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
    }

    /** @return the constant, or null (with a WARN) when the stored value is not one */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String ruleId, String column) {
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
