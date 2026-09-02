package com.auditflow.alerting.dispatch;

import com.auditflow.common.model.AuditEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** The feed: enriched events from enrichment-service, one dispatch each. */
@Component
public class EnrichedEventListener {

    private final AlertDispatcher dispatcher;

    public EnrichedEventListener(AlertDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = "${audit.alerting.enriched-topic}")
    public void onEnrichedEvent(AuditEvent event) {
        dispatcher.dispatch(event);
    }
}
