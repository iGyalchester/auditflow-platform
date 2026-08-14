package com.auditflow.alerting.notifiers;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exercises EmailNotifier against a real in-process SMTP server (GreenMail)
 * rather than a mocked JavaMailSender.
 */
class EmailNotifierTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

    private final AlertRule rule = AlertRule.builder()
            .ruleId("rule-1")
            .customerId("cust-1")
            .name("High-risk exports")
            .riskThreshold(RiskLevel.HIGH)
            .build();

    private final AuditEvent event = AuditEvent.builder()
            .eventId("evt-1")
            .customerId("cust-1")
            .type(EventType.DATA_EXPORT)
            .resource("customers_table")
            .riskLevel(RiskLevel.HIGH)
            .timestamp(Instant.now())
            .build();

    private JavaMailSenderImpl smtpSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(GREEN_MAIL.getSmtp().getPort());
        return sender;
    }

    @Test
    void sendsAlertEmailOverSmtp() throws Exception {
        EmailNotifier notifier = new EmailNotifier(
                smtpSender(), "alerts@auditflow.local", "security@customer.test");

        notifier.notify(rule, event);

        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).contains("High-risk exports");
        assertThat(received[0].getContent().toString()).contains("evt-1");
    }

    @Test
    void fallsBackToLoggingWhenSmtpNotConfigured() {
        EmailNotifier notifier = new EmailNotifier(null, "alerts@auditflow.local", "someone@customer.test");

        assertThatCode(() -> notifier.notify(rule, event)).doesNotThrowAnyException();
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    void fallsBackToLoggingWhenNoRecipientConfigured() {
        EmailNotifier notifier = new EmailNotifier(smtpSender(), "alerts@auditflow.local", "");

        assertThatCode(() -> notifier.notify(rule, event)).doesNotThrowAnyException();
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }
}
