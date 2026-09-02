package com.auditflow.alerting.notifiers;

import com.auditflow.alerting.AlertingProperties;
import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AlertRule;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class EmailNotifierTest {

    private final SesClient ses = mock(SesClient.class);
    private final ObjectProvider<SesClient> provider = mock(ObjectProvider.class);

    private final AlertRule rule = AlertRule.builder().ruleId("r2").customerId("acme").name("PII viewed")
            .notificationChannels(List.of("email")).build();
    private final AuditEvent event = AuditEvent.builder().eventId("evt-8").customerId("acme")
            .type(EventType.FILE_ACCESS).action("PROFILE_VIEW").resource("profile:42")
            .riskLevel(RiskLevel.MEDIUM).timestamp(Instant.now()).build();

    @Test
    void sendsThroughSesWithConfiguredSenderAndRecipients() {
        when(provider.getIfAvailable()).thenReturn(ses);
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m").build());
        EmailNotifier notifier = new EmailNotifier(new AlertingProperties("t", "", new AlertingProperties.Slack(""),
                new AlertingProperties.Email("alerts@auditflow.example", List.of("sec@acme.example"), "us-east-1")), provider);

        notifier.notify(rule, event);

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        assertThat(sent.getValue().source()).isEqualTo("alerts@auditflow.example");
        assertThat(sent.getValue().destination().toAddresses()).containsExactly("sec@acme.example");
        assertThat(sent.getValue().message().subject().data()).isEqualTo("[AuditFlow] PII viewed - MEDIUM risk for acme");
        assertThat(sent.getValue().message().body().text().data()).contains("PROFILE_VIEW").contains("profile:42");
    }

    @Test
    void withoutASenderItOnlyLogs() {
        EmailNotifier notifier = new EmailNotifier(new AlertingProperties("t", "", new AlertingProperties.Slack(""),
                new AlertingProperties.Email("", List.of(), "")), provider);

        notifier.notify(rule, event);

        verifyNoInteractions(ses);
    }
}
