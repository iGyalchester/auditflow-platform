package com.auditflow.alerting.rules;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.rules.AlertRuleRows;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
// Seeding must happen before the first load, or a fresh environment starts
// with no rules. That used to be expressed as an unused constructor
// parameter, which reads like a mistake and invites deletion; this says the
// same thing and says why.
@DependsOn("ruleSeeder")
public class JdbcRuleRepository implements RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleRepository.class);

    static final String SELECT_SQL = "SELECT " + AlertRuleRows.COLUMNS + " FROM alert_rules";

    private final JdbcTemplate jdbcTemplate;
    private volatile Map<String, List<AlertRule>> byCustomer = Map.of();
    private volatile boolean loadedOnce;

    public JdbcRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${audit.alerting.rules-refresh:PT30S}", initialDelayString = "${audit.alerting.rules-refresh:PT30S}")
    public void refresh() {
        try {
            List<AlertRule> rules = jdbcTemplate.query(SELECT_SQL, AlertRuleRows.MAPPER).stream()
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

}
