package com.auditflow.alerting.rules;

import com.auditflow.alerting.AlertingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileRuleRepositoryTest {

    private FileRuleRepository repo(String location) {
        FileRuleRepository repo = new FileRuleRepository(new DefaultResourceLoader(), new ObjectMapper(),
                mock(JdbcTemplate.class), new AlertingProperties("t", location, new AlertingProperties.Slack(""),
                        new AlertingProperties.Email("", List.of(), "")));
        repo.load();
        return repo;
    }

    @Test
    void loadsRulesGroupedByCustomer() {
        FileRuleRepository repo = repo("classpath:test-rules.json");

        assertThat(repo.rulesFor("resistance")).hasSize(2)
                .extracting("ruleId").containsExactlyInAnyOrder("login-failures", "pii-view");
        assertThat(repo.rulesFor("other-co")).hasSize(1);
        assertThat(repo.rulesFor("nobody")).isEmpty();
    }

    @Test
    void missingOrBlankLocationMeansNoRulesNotAStartupFailure() {
        assertThat(repo("classpath:does-not-exist.json").rulesFor("resistance")).isEmpty();
        assertThat(repo("").rulesFor("resistance")).isEmpty();
    }
}
