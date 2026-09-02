package com.auditflow.enrichment.retention;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The purge SQL against a real Postgres: old rows go, recent rows stay. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"audit.retention.days=30", "audit.retention.batch-size=2"})
class RetentionPurgeJobIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private RetentionPurgeJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesOnlyRowsPastTheWindowAcrossSeveralBatches() {
        Instant now = Instant.now();
        insert("old-1", now.minus(Duration.ofDays(31)));
        insert("old-2", now.minus(Duration.ofDays(60)));
        insert("old-3", now.minus(Duration.ofDays(90)));
        insert("recent-1", now.minus(Duration.ofDays(29)));
        insert("recent-2", now);

        long deleted = job.purge();

        assertThat(deleted).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT event_id FROM audit_events ORDER BY event_id", String.class))
                .containsExactly("recent-1", "recent-2");
    }

    private void insert(String id, Instant occurredAt) {
        jdbc.update("INSERT INTO audit_events (event_id, customer_id, occurred_at, event_type) VALUES (?, ?, ?, ?)",
                id, "cust-1", Timestamp.from(occurredAt), "AUTH_EVENT");
    }
}
