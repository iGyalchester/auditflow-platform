package com.auditflow.alerting;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end alerting check against real Kafka and Postgres: an enriched
 * event published to the enriched-events topic must match the seeded rule,
 * trigger the (fallback-logging) notifiers, and land in alert_history.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AlertingIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auditflow")
            .withUsername("auditflow")
            .withPassword("auditflow");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS alert_rules (
                    rule_id       VARCHAR(64) PRIMARY KEY,
                    customer_id   VARCHAR(64) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    event_type    VARCHAR(32),
                    risk_threshold VARCHAR(16),
                    condition_expression TEXT,
                    enabled       BOOLEAN NOT NULL DEFAULT true,
                    notification_channels VARCHAR(255)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS alert_history (
                    alert_id      VARCHAR(64) PRIMARY KEY,
                    rule_id       VARCHAR(64) NOT NULL REFERENCES alert_rules(rule_id),
                    event_id      VARCHAR(64) NOT NULL,
                    customer_id   VARCHAR(64) NOT NULL,
                    triggered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    notified_channels VARCHAR(255)
                )
                """);
        jdbcTemplate.update("DELETE FROM alert_history");
        jdbcTemplate.update("DELETE FROM alert_rules");
        jdbcTemplate.update("""
                INSERT INTO alert_rules
                    (rule_id, customer_id, name, event_type, risk_threshold, enabled, notification_channels)
                VALUES ('rule-1', 'cust-1', 'High-risk exports', 'DATA_EXPORT', 'HIGH', true, 'slack')
                """);
    }

    @Test
    void enrichedEventTriggersAlertAndRecordsHistory() throws Exception {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-alert-1")
                .customerId("cust-1")
                .userId("user-1")
                .type(EventType.DATA_EXPORT)
                .resource("customers_table")
                .action("EXPORT")
                .riskLevel(RiskLevel.HIGH)
                .anomalous(true)
                .timestamp(Instant.now())
                .build();

        publishEnrichedEvent(event);

        List<Map<String, Object>> rows = awaitAlertHistoryRows(Duration.ofSeconds(20));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("rule_id")).isEqualTo("rule-1");
        assertThat(rows.get(0).get("event_id")).isEqualTo("evt-alert-1");
        assertThat(rows.get(0).get("customer_id")).isEqualTo("cust-1");
        assertThat(rows.get(0).get("notified_channels")).isEqualTo("slack");
    }

    private void publishEnrichedEvent(AuditEvent event) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        KafkaTemplate<String, AuditEvent> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        template.send("enriched-events", event.getCustomerId(), event);
        template.flush();
    }

    private List<Map<String, Object>> awaitAlertHistoryRows(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM alert_history");
            if (!rows.isEmpty()) {
                return rows;
            }
            Thread.sleep(250);
        }
        return List.of();
    }
}
