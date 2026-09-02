package com.auditflow.alerting.notifiers;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

/**
 * Sends alerts through Amazon SES. Without a configured sender it logs
 * the alert instead (dev default). SES in sandbox mode only delivers to
 * verified addresses - a deployment detail, not something this class can
 * paper over.
 */
@Component
public class EmailNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final SesClient sesClient;
    private final AlertingProperties.Email email;

    public EmailNotifier(AlertingProperties props, ObjectProvider<SesClient> sesClient) {
        this.email = props.email();
        this.sesClient = email != null && email.configured() ? sesClient.getIfAvailable() : null;
    }

    @Override
    public void notify(AlertRule rule, AuditEvent event) {
        if (sesClient == null) {
            log.info("[email:not configured] {}", AlertMessage.subject(rule, event));
            return;
        }
        sesClient.sendEmail(SendEmailRequest.builder()
                .source(email.from())
                .destination(Destination.builder().toAddresses(email.to()).build())
                .message(Message.builder()
                        .subject(Content.builder().data(AlertMessage.subject(rule, event)).build())
                        .body(Body.builder().text(Content.builder().data(AlertMessage.body(rule, event)).build()).build())
                        .build())
                .build());
        log.info("[email] sent rule={} event={} to {} recipient(s)", rule.getRuleId(), event.getEventId(), email.to().size());
    }

    @Override
    public String channel() {
        return "email";
    }
}
