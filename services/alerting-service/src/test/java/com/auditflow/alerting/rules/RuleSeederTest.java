package com.auditflow.alerting.rules;

import com.auditflow.alerting.AlertingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RuleSeederTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private RuleSeeder seeder(String location) {
        RuleSeeder seeder = new RuleSeeder(new DefaultResourceLoader(), new ObjectMapper(), jdbc,
                new AlertingProperties("t", location, new AlertingProperties.Slack(""),
                        new AlertingProperties.Email("", List.of(), "")));
        seeder.load();
        return seeder;
    }

    @Test
    void upsertsEveryRuleFromTheFile() {
        RuleSeeder seeder = seeder("classpath:test-rules.json");

        assertThat(seeder.seededRules()).hasSize(3);
        verify(jdbc, times(3)).update(eq(RuleSeeder.UPSERT_SQL), any(Object[].class));
        verify(jdbc).update(eq(RuleSeeder.UPSERT_SQL), eq("pii-view"), eq("resistance"), eq("PII viewed"), any(),
                eq("FILE_ACCESS"), eq("HIGH"), any(), eq(true), eq("slack,email"));
    }

    @Test
    void missingOrBlankLocationSeedsNothingWithoutFailing() {
        assertThat(seeder("classpath:does-not-exist.json").seededRules()).isEmpty();
        assertThat(seeder("").seededRules()).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
