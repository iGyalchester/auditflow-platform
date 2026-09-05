package com.auditflow.alerting.rules;

import com.auditflow.alerting.AlertingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Seeding fills an empty customer, once. It used to upsert on every start,
 * which made the file the source of truth instead of the table: an edit
 * through the API was reverted by the next deploy, and a rule someone
 * disabled came back enabled.
 */
class RuleSeederTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private void seed(String location) {
        new RuleSeeder(new DefaultResourceLoader(), new ObjectMapper(), jdbc,
                new AlertingProperties("t", location, new AlertingProperties.Slack(""),
                        new AlertingProperties.Email("", List.of(), ""))).load();
    }

    @Test
    void anEmptyCustomerIsSeededFromTheFile() {
        when(jdbc.queryForObject(eq(RuleSeeder.COUNT_SQL), eq(Integer.class), anyString())).thenReturn(0);
        when(jdbc.update(eq(RuleSeeder.INSERT_SQL), any(Object[].class))).thenReturn(1);

        seed("classpath:test-rules.json");

        verify(jdbc, times(3)).update(eq(RuleSeeder.INSERT_SQL), any(Object[].class));
        verify(jdbc).update(eq(RuleSeeder.INSERT_SQL), eq("pii-view"), eq("resistance"), eq("PII viewed"), any(),
                eq("FILE_ACCESS"), eq("HIGH"), any(), eq(true), eq("slack,email"));
    }

    @Test
    void aCustomerThatAlreadyHasRulesIsLeftAlone() {
        // the whole point: their edits and their disabled rules survive a deploy
        when(jdbc.queryForObject(eq(RuleSeeder.COUNT_SQL), eq(Integer.class), anyString())).thenReturn(2);

        seed("classpath:test-rules.json");

        verify(jdbc, never()).update(eq(RuleSeeder.INSERT_SQL), any(Object[].class));
    }

    @Test
    void oneUnusableRuleDoesNotStopTheRest() {
        when(jdbc.queryForObject(eq(RuleSeeder.COUNT_SQL), eq(Integer.class), anyString())).thenReturn(0);
        when(jdbc.update(eq(RuleSeeder.INSERT_SQL), any(Object[].class)))
                .thenThrow(new RuntimeException("constraint violation"))
                .thenReturn(1);

        seed("classpath:test-rules.json");

        // all three attempted; the failure did not abort seeding
        verify(jdbc, times(3)).update(eq(RuleSeeder.INSERT_SQL), any(Object[].class));
    }

    @Test
    void missingOrBlankLocationSeedsNothingWithoutFailing() {
        seed("classpath:does-not-exist.json");
        seed("");

        verifyNoInteractions(jdbc);
    }

    @Test
    void anIdAlreadyOwnedByAnotherCustomerIsSkippedNotFatal() {
        when(jdbc.queryForObject(eq(RuleSeeder.COUNT_SQL), eq(Integer.class), anyString())).thenReturn(0);
        // ON CONFLICT DO NOTHING reports zero rows written
        when(jdbc.update(eq(RuleSeeder.INSERT_SQL), any(Object[].class))).thenReturn(0);

        seed("classpath:test-rules.json");

        verify(jdbc, times(3)).update(eq(RuleSeeder.INSERT_SQL), any(Object[].class));
    }
}
