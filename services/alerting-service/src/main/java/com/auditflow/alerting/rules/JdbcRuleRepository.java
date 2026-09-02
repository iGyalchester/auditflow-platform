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
import java.util.stream.Collectors;

/**
 * The rules alerting actually matches against: the alert_rules table,
 * read in full into memory and refreshed on a timer. A rule created or
 * changed through the gateway's API is live within one refresh interval
 * (30 s by default) - no restart, no redeploy. A refresh that fails keeps
 * the last good set, so a database blip never drops every rule.
 */
@Component
public class JdbcRuleRepository implements RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleRepository.class);

    static final String SELECT_SQL = """
            SELECT rule_id, customer_id, name, description, event_type, risk_threshold,
                   condition_expression, enabled, notification_channels
            FROM alert_rules
            """;

    static final RowMapper<AlertRule> ROW_MAPPER = (rs, i) -> AlertRule.builder()
            .ruleId(rs.getString("rule_id"))
            .customerId(rs.getString("customer_id"))
            .name(rs.getString("name"))
            .description(rs.getString("description"))
            .eventType(rs.getString("event_type") != null ? EventType.valueOf(rs.getString("event_type")) : null)
            .riskThreshold(rs.getString("risk_threshold") != null ? RiskLevel.valueOf(rs.getString("risk_threshold")) : null)
            .conditionExpression(rs.getString("condition_expression"))
            .enabled(rs.getBoolean("enabled"))
            .notificationChannels(channels(rs.getString("notification_channels")))
            .build();

    private final JdbcTemplate jdbcTemplate;
    private volatile Map<String, List<AlertRule>> byCustomer = Map.of();

    /** The seeder is a constructor dependency only to guarantee it runs first. */
    public JdbcRuleRepository(JdbcTemplate jdbcTemplate, RuleSeeder seeder) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${audit.alerting.rules-refresh:PT30S}", initialDelayString = "${audit.alerting.rules-refresh:PT30S}")
    public void refresh() {
        try {
            List<AlertRule> rules = jdbcTemplate.query(SELECT_SQL, ROW_MAPPER);
            byCustomer = rules.stream().collect(Collectors.groupingBy(AlertRule::getCustomerId));
            log.debug("Loaded {} alert rules for {} customers", rules.size(), byCustomer.size());
        } catch (Exception e) {
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
}
