package com.auditflow.agent;

import com.auditflow.agent.collector.CommittableCollector;
import com.auditflow.agent.publish.IngestionPublisher;
import com.auditflow.common.interfaces.EventCollector;
import com.auditflow.common.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Poll loop: every configured collector gathers its new events and the
 * batch goes through the authenticated ingestion endpoint. Delivery is
 * at-least-once toward ingestion (a failed publish is retried on the next
 * poll via the collector's checkpoint) and deduplicated downstream by the
 * deterministic event ids + the Aurora sink's idempotent insert.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final List<EventCollector> collectors;
    private final IngestionPublisher publisher;

    public AgentRunner(List<EventCollector> collectors, IngestionPublisher publisher) {
        this.collectors = collectors;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${agent.poll-ms:15000}")
    public void poll() {
        for (EventCollector collector : collectors) {
            try {
                List<AuditEvent> events = collector.collect();
                // an empty batch (all rows filtered as noise) still moves the
                // checkpoint - there is nothing to lose by committing it
                boolean delivered = events.isEmpty() || publisher.publish(events);
                if (delivered && collector instanceof CommittableCollector committable) {
                    committable.commit();
                }
                if (!events.isEmpty()) {
                    log.info("{}: collected {} event(s), delivered={}",
                            collector.sourceName(), events.size(), delivered);
                }
            } catch (Exception e) {
                log.warn("{}: collection failed - will retry next poll: {}",
                        collector.sourceName(), e.getMessage());
            }
        }
    }
}
