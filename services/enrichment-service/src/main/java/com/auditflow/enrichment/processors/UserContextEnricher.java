package com.auditflow.enrichment.processors;

import com.auditflow.common.interfaces.EventProcessor;
import com.auditflow.common.model.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Attaches user/session context to an event. A future iteration will look
 * users up against a customer's identity provider; for now it stamps a
 * processing marker so downstream stages can see the pipeline ran.
 */
@Component
public class UserContextEnricher implements EventProcessor {

    @Override
    public AuditEvent process(AuditEvent event) {
        Map<String, String> tags = new HashMap<>(event.getTags());
        tags.putIfAbsent("enrichment.userContext", event.getUserId() != null ? "resolved" : "unknown");
        return event.toBuilder().tags(tags).build();
    }

    @Override
    public int order() {
        return 10;
    }
}
