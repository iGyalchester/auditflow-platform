package com.auditflow.alerting.notifiers;

import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends alert emails via SMTP. Spring Boot only creates a
 * {@link JavaMailSender} bean when {@code spring.mail.host} is configured;
 * when it is absent (local/dev/CI) - or no recipient is set - this falls
 * back to logging so the service never requires SMTP just to boot. Delivery
 * failures are logged, not propagated, so one dead channel cannot stop the
 * other notifiers or the alert pipeline.
 */
@Component
public class EmailNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String to;

    public EmailNotifier(@Nullable JavaMailSender mailSender,
                         @Value("${alerting.email.from:alerts@auditflow.local}") String from,
                         @Value("${alerting.email.to:}") String to) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
    }

    @Override
    public void notify(AlertRule rule, AuditEvent event) {
        String subject = "AuditFlow alert: %s [%s]".formatted(rule.getName(), event.getRiskLevel());
        String body = """
                Alert rule '%s' (%s) matched an audit event.

                Customer:  %s
                Event:     %s
                Type:      %s
                Resource:  %s
                Action:    %s
                Risk:      %s
                Anomalous: %s
                """.formatted(rule.getName(), rule.getRuleId(), event.getCustomerId(),
                event.getEventId(), event.getType(), event.getResource(),
                event.getAction(), event.getRiskLevel(), event.isAnomalous());

        if (mailSender == null || to.isBlank()) {
            log.info("[email:fallback] SMTP or recipient not configured - {} / event={}",
                    subject, event.getEventId());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.debug("[email] delivered alert for rule={} event={}", rule.getRuleId(), event.getEventId());
        } catch (MailException e) {
            log.error("[email] failed to deliver alert for rule={} event={}",
                    rule.getRuleId(), event.getEventId(), e);
        }
    }

    @Override
    public String channel() {
        return "email";
    }
}
