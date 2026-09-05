package com.auditflow.alerting.rules;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.rules.AlertRuleRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a fresh environment its first alert rules, from a JSON file on the
 * classpath or filesystem. The table is the source of truth -
 * {@link JdbcRuleRepository} reads it, and the gateway's API writes it -
 * so this only fills an empty one.
 *
 * <p>It used to upsert on every start, which quietly made the file the
 * source of truth instead. Edit a seeded rule through the API and the next
 * deploy reverted it; disable one and it came back enabled; the API said
 * 200 and the change lasted until the next restart. Worse for a rule
 * someone deliberately turned off after it paged them at 3am.
 *
 * <p>So seeding is now per customer and once: a customer with any rule at
 * all is left alone. A customer added to the file later still gets theirs,
 * because the check is per customer rather than "is the table empty".
 */
@Component
public class RuleSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleSeeder.class);

    static final String INSERT_SQL =
            "INSERT INTO alert_rules (" + AlertRuleRows.COLUMNS + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (rule_id) DO NOTHING";

    static final String COUNT_SQL = "SELECT count(*) FROM alert_rules WHERE customer_id = ?";

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final String location;

    public RuleSeeder(ResourceLoader resourceLoader, ObjectMapper objectMapper,
                      JdbcTemplate jdbcTemplate, AlertingProperties props) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.location = props.rulesFile();
    }

    @PostConstruct
    public void load() {
        if (location == null || location.isBlank()) {
            log.info("No rules seed file configured (audit.alerting.rules-file); rules come from alert_rules only");
            return;
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Rules seed file {} not found; rules come from alert_rules only", location);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            List<AlertRule> rules = objectMapper.readValue(in,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AlertRule.class));
            log.info("Read {} seed rules from {}", rules.size(), location);
            syncToDatabase(rules);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read rules file " + location, e);
        }
    }

    private void syncToDatabase(List<AlertRule> rules) {
        Map<String, List<AlertRule>> byCustomer = new LinkedHashMap<>();
        for (AlertRule rule : rules) {
            byCustomer.computeIfAbsent(rule.getCustomerId(), c -> new ArrayList<>()).add(rule);
        }

        byCustomer.forEach((customerId, customerRules) -> {
            Integer existing = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class, customerId);
            if (existing != null && existing > 0) {
                log.info("Customer {} already has {} rule(s); leaving them alone", customerId, existing);
                return;
            }
            int inserted = 0;
            for (AlertRule rule : customerRules) {
                // one bad row must not cost the rest: a rule the file gets
                // wrong should not stop a fresh environment being seeded
                try {
                    int updated = jdbcTemplate.update(INSERT_SQL, AlertRuleRows.insertParams(rule));
                    if (updated == 0) {
                        // rule_id is globally unique, so this means the id is
                        // already taken - by another customer
                        log.warn("Seed rule '{}' for customer {} was not inserted; that rule id already exists",
                                rule.getRuleId(), customerId);
                    } else {
                        inserted++;
                    }
                } catch (Exception e) {
                    log.error("Could not seed rule '{}' for customer {}: {}",
                            rule.getRuleId(), customerId, e.toString());
                }
            }
            log.info("Seeded {} rule(s) for customer {}", inserted, customerId);
        });
    }
}
