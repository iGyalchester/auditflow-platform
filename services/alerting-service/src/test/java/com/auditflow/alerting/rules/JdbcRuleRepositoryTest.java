package com.auditflow.alerting.rules;

import com.auditflow.common.model.AlertRule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class JdbcRuleRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcRuleRepository repo = new JdbcRuleRepository(jdbc);

    private JdbcRuleRepository freshRepo() {
        return new JdbcRuleRepository(jdbc);
    }

    private static AlertRule rule(String id, String customer) {
        return AlertRule.builder().ruleId(id).customerId(customer).name(id).build();
    }

    @Test
    void groupsRulesByCustomerOnRefresh() {
        when(jdbc.query(eq(JdbcRuleRepository.SELECT_SQL), any(RowMapper.class)))
                .thenReturn(List.of(rule("a1", "acme"), rule("a2", "acme"), rule("o1", "other")));

        repo.refresh();

        assertThat(repo.rulesFor("acme")).extracting(AlertRule::getRuleId).containsExactly("a1", "a2");
        assertThat(repo.rulesFor("other")).hasSize(1);
        assertThat(repo.rulesFor("nobody")).isEmpty();
    }

    @Test
    void aFailedRefreshKeepsThePreviousRules() {
        when(jdbc.query(eq(JdbcRuleRepository.SELECT_SQL), any(RowMapper.class)))
                .thenReturn(List.of(rule("a1", "acme")))
                .thenThrow(new RuntimeException("db down"));

        repo.refresh();
        repo.refresh();

        assertThat(repo.rulesFor("acme")).hasSize(1);
    }

    @Test
    void aFailedFirstLoadFailsFastInsteadOfMatchingNothing() {
        // Starting with an empty rule set is silent: the service looks
        // healthy and every alert is missed. Refuse to start instead.
        when(jdbc.query(eq(JdbcRuleRepository.SELECT_SQL), any(RowMapper.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(freshRepo()::refresh)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start with no rules");
    }

}
