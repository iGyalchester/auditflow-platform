package com.auditflow.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The pull-based half of AuditFlow's intake story. Applications you own
 * push their own events to ingestion-service; this agent exists for the
 * systems you can't modify - it sits next to a source database, tails its
 * query log, normalizes rows into AuditEvents, and forwards them through
 * the same authenticated ingestion front door as everything else.
 *
 * First real collector: MySQL's general log (log_output=TABLE). The
 * planned Postgres and generic-API collectors implement the same
 * {@link com.auditflow.common.interfaces.EventCollector} seam.
 */
@SpringBootApplication
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
