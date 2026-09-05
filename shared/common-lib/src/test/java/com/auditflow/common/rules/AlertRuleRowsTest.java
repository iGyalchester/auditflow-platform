package com.auditflow.common.rules;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The codec three call sites used to implement separately.
 *
 * <p>The ResultSet here is a Mockito mock, which is not a violation of the
 * no-mocking-internal-components rule: it is a JDBC interface, i.e. the
 * boundary, not our own code. The alternative would be a Postgres container
 * for what is a pure mapping function - and the repository integration
 * tests already exercise it against a real database.
 */
class AlertRuleRowsTest {

    private static ResultSet row(String eventType, String riskThreshold, String channels) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("rule_id")).thenReturn("r1");
        when(rs.getString("customer_id")).thenReturn("acme");
        when(rs.getString("name")).thenReturn("Failed logins");
        when(rs.getString("description")).thenReturn("three in a row");
        when(rs.getString("event_type")).thenReturn(eventType);
        when(rs.getString("risk_threshold")).thenReturn(riskThreshold);
        when(rs.getString("condition_expression")).thenReturn("action == 'LOGIN_FAILURE'");
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getString("notification_channels")).thenReturn(channels);
        return rs;
    }

    @Test
    void mapsACompleteRow() throws Exception {
        AlertRule rule = AlertRuleRows.MAPPER.mapRow(row("AUTH_EVENT", "HIGH", "slack, email"), 0);

        assertThat(rule).isNotNull();
        assertThat(rule.getRuleId()).isEqualTo("r1");
        assertThat(rule.getCustomerId()).isEqualTo("acme");
        assertThat(rule.getEventType()).isEqualTo(EventType.AUTH_EVENT);
        assertThat(rule.getRiskThreshold()).isEqualTo(RiskLevel.HIGH);
        assertThat(rule.isEnabled()).isTrue();
        assertThat(rule.getNotificationChannels()).containsExactly("slack", "email");
    }

    @Test
    void nullEnumsAreNotAnError() throws Exception {
        AlertRule rule = AlertRuleRows.MAPPER.mapRow(row(null, null, null), 0);

        assertThat(rule).isNotNull();
        assertThat(rule.getEventType()).isNull();
        assertThat(rule.getRiskThreshold()).isNull();
        assertThat(rule.getNotificationChannels()).isEmpty();
    }

    /**
     * The behaviour worth having in one place. A stored value the enum does
     * not know used to throw out of the row mapper and abort the whole
     * query - so one bad row emptied the rule set and silently disarmed
     * every alert for every customer. A null row is dropped by the caller
     * and the rest keep working.
     */
    @Test
    void anUnknownEnumYieldsANullRowRatherThanThrowing() throws Exception {
        assertThat(AlertRuleRows.MAPPER.mapRow(row("NOT_A_TYPE", "HIGH", null), 0)).isNull();
        assertThat(AlertRuleRows.MAPPER.mapRow(row("AUTH_EVENT", "NOPE", null), 0)).isNull();
    }

    @Test
    void insertParamsMatchTheColumnOrder() {
        AlertRule rule = AlertRule.builder()
                .ruleId("r1").customerId("acme").name("Failed logins").description("three in a row")
                .eventType(EventType.AUTH_EVENT).riskThreshold(RiskLevel.HIGH)
                .conditionExpression("action == 'LOGIN_FAILURE'").enabled(true)
                .notificationChannels(List.of("slack", "email"))
                .build();

        assertThat(AlertRuleRows.insertParams(rule)).containsExactly(
                "r1", "acme", "Failed logins", "three in a row",
                "AUTH_EVENT", "HIGH", "action == 'LOGIN_FAILURE'", true, "slack,email");

        // as many placeholders as columns, or the bind silently shifts
        assertThat(AlertRuleRows.COLUMNS.split(",")).hasSize(AlertRuleRows.insertParams(rule).length);
    }

    @Test
    void nullEnumsBindAsNullNotAsTheStringNull() {
        AlertRule rule = AlertRule.builder().ruleId("r1").customerId("acme").name("n").build();

        assertThat(AlertRuleRows.insertParams(rule)[4]).isNull();
        assertThat(AlertRuleRows.insertParams(rule)[5]).isNull();
    }

    @Test
    void channelsRoundTripThroughTheCsvColumn() {
        assertThat(AlertRuleRows.splitChannels("slack, email")).containsExactly("slack", "email");
        assertThat(AlertRuleRows.splitChannels("")).isEmpty();
        assertThat(AlertRuleRows.splitChannels(null)).isEmpty();
        assertThat(AlertRuleRows.joinChannels(List.of("slack", "email"))).isEqualTo("slack,email");
        assertThat(AlertRuleRows.joinChannels(null)).isEmpty();
        assertThat(AlertRuleRows.splitChannels(AlertRuleRows.joinChannels(List.of("slack", "email"))))
                .containsExactly("slack", "email");
    }

    @Test
    void parseEnumAcceptsAKnownValueAndRejectsAnythingElse() {
        assertThat(AlertRuleRows.parseEnum(EventType.class, "DATA_EXPORT", "r1", "event_type"))
                .isEqualTo(EventType.DATA_EXPORT);
        assertThat(AlertRuleRows.parseEnum(EventType.class, null, "r1", "event_type")).isNull();
        assertThat(AlertRuleRows.parseEnum(EventType.class, "NOT_A_TYPE", "r1", "event_type")).isNull();
        assertThat(AlertRuleRows.parseEnum(RiskLevel.class, "NOPE", "r1", "risk_threshold")).isNull();
    }
}
