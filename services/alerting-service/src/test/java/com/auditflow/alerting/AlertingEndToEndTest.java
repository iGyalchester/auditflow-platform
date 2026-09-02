package com.auditflow.alerting;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.enums.RiskLevel;
import com.auditflow.common.model.AuditEvent;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole service, wired for real: an enriched event lands on the topic
 * (embedded Kafka), the listener consumes it, the file rules match, and the
 * real SlackNotifier posts to a webhook - here an in-test HTTP server.
 */
@SpringBootTest(properties = {
        "audit.alerting.rules-file=classpath:test-rules.json",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.sql.init.mode=never"
})
@EmbeddedKafka(partitions = 1, topics = "audit-events-enriched")
class AlertingEndToEndTest {

    /** No database in this test: rule sync and history go to a mock. */
    @MockBean
    private JdbcTemplate jdbcTemplate;

    private static final HttpServer SLACK;
    private static final CompletableFuture<String> POSTED = new CompletableFuture<>();

    static {
        try {
            SLACK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SLACK.createContext("/hook", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    POSTED.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            SLACK.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void slack(DynamicPropertyRegistry registry) {
        registry.add("audit.alerting.slack.webhook-url",
                () -> "http://127.0.0.1:" + SLACK.getAddress().getPort() + "/hook");
    }

    @AfterAll
    static void stop() {
        SLACK.stop(0);
    }

    @Test
    void enrichedLoginFailureReachesSlack(@org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}") String brokers)
            throws Exception {
        KafkaTemplate<String, AuditEvent> producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)));

        AuditEvent enriched = AuditEvent.builder()
                .eventId("evt-e2e").customerId("resistance").type(EventType.AUTH_EVENT)
                .action("LOGIN_FAILURE").userId("boris@example").ipAddress("203.0.113.7")
                .riskLevel(RiskLevel.MEDIUM).timestamp(Instant.now()).build();
        producer.send("audit-events-enriched", "resistance", enriched).get(10, TimeUnit.SECONDS);

        String slackBody = POSTED.get(30, TimeUnit.SECONDS);
        assertThat(slackBody).contains("Failed login").contains("evt-e2e").contains("LOGIN_FAILURE");
    }
}
