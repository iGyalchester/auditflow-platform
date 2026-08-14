package com.auditflow.alerting.adapters;

import com.auditflow.alerting.rules.AlertDispatcher;
import com.auditflow.common.model.AuditEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes fully-enriched events from enrichment-service and hands them to
 * the alert dispatcher.
 */
@Component
public class EnrichedEventConsumer {

    private final AlertDispatcher alertDispatcher;

    public EnrichedEventConsumer(AlertDispatcher alertDispatcher) {
        this.alertDispatcher = alertDispatcher;
    }

    @KafkaListener(topics = "${audit.enrichment.topic}")
    public void onMessage(AuditEvent event) {
        alertDispatcher.dispatch(event);
    }
}
