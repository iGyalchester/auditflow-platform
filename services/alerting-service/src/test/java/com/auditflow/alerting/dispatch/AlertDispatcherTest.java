package com.auditflow.alerting.dispatch;

import com.auditflow.alerting.history.AlertHistoryWriter;
import com.auditflow.alerting.notifiers.AlertNotifier;
import com.auditflow.common.rules.ConditionEvaluator;
import com.auditflow.alerting.rules.RuleEngine;
import com.auditflow.alerting.rules.RuleRepository;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertDispatcherTest {

    private final AlertNotifier slack = notifier("slack");
    private final AlertNotifier email = notifier("email");
    private final RuleRepository rules = mock(RuleRepository.class);
    private final AlertHistoryWriter history = mock(AlertHistoryWriter.class);
    private final AlertDispatcher dispatcher = new AlertDispatcher(
            rules, new RuleEngine(new ConditionEvaluator()), List.of(slack, email), history);

    private static AlertNotifier notifier(String channel) {
        AlertNotifier n = mock(AlertNotifier.class);
        when(n.channel()).thenReturn(channel);
        return n;
    }

    private static AuditEvent loginFailure(String customer) {
        return AuditEvent.builder().eventId("evt-1").customerId(customer).type(EventType.AUTH_EVENT)
                .action("LOGIN_FAILURE").riskLevel(RiskLevel.MEDIUM).timestamp(Instant.now()).build();
    }

    private static AlertRule rule(String id, String customer, String condition, String... channels) {
        return AlertRule.builder().ruleId(id).customerId(customer).eventType(EventType.AUTH_EVENT)
                .conditionExpression(condition).notificationChannels(List.of(channels)).build();
    }

    @Test
    void matchingRuleFiresOnEveryChannelItNames() {
        when(rules.rulesFor("acme")).thenReturn(List.of(rule("r1", "acme", "action == 'LOGIN_FAILURE'", "slack", "email")));

        assertThat(dispatcher.dispatch(loginFailure("acme"))).isEqualTo(2);
        verify(slack).notify(any(), any());
        verify(email).notify(any(), any());
        verify(history).record(any(), any(), eq(List.of("slack", "email")));
    }

    @Test
    void nonMatchingRuleFiresNothing() {
        when(rules.rulesFor("acme")).thenReturn(List.of(rule("r1", "acme", "action == 'LOGIN_SUCCESS'", "slack")));

        assertThat(dispatcher.dispatch(loginFailure("acme"))).isZero();
        verify(slack, never()).notify(any(), any());
        verify(history, never()).record(any(), any(), any());
    }

    @Test
    void unknownChannelIsSkippedNotFatal() {
        when(rules.rulesFor("acme")).thenReturn(List.of(rule("r1", "acme", null, "pager", "slack")));

        assertThat(dispatcher.dispatch(loginFailure("acme"))).isEqualTo(1);
        verify(slack).notify(any(), any());
    }

    @Test
    void oneFailingChannelDoesNotStopTheOthers() {
        when(rules.rulesFor("acme")).thenReturn(List.of(rule("r1", "acme", null, "slack", "email")));
        doThrow(new RuntimeException("slack is down")).when(slack).notify(any(), any());

        assertThat(dispatcher.dispatch(loginFailure("acme"))).isEqualTo(2);
        verify(email).notify(any(), any());
        // the record names only the channel that got through
        verify(history).record(any(), any(), eq(List.of("email")));
    }

    @Test
    void rulesAreLookedUpForTheEventsCustomerOnly() {
        when(rules.rulesFor("acme")).thenReturn(List.of());

        assertThat(dispatcher.dispatch(loginFailure("acme"))).isZero();
        verify(rules).rulesFor("acme");
    }
}
