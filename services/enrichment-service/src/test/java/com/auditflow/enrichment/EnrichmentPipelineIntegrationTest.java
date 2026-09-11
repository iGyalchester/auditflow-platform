package com.auditflow.enrichment;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What happens to an event the pipeline cannot handle.
 *
 * <p>Before this there was no error handler, so the container retried ten
 * times with no pause and then committed the offset - the event was gone,
 * with a log line as its only trace. These tests are the reason the
 * configuration exists: a transient failure must be survived, and a
 * permanent one must end up somewhere it can be found.
 *
 * <p>A real broker and a real Postgres, because retries, offset commits and
 * dead-letter publishing are container behaviour that a mocked
 * KafkaTemplate cannot demonstrate. S3 is the one stub: it is a third-party
 * boundary, not our code.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // seconds, not the production ~40, so the suite stays quick
        "audit.enrichment.retry.initial-interval=50ms",
        "audit.enrichment.retry.max-interval=100ms",
        "audit.enrichment.retry.max-retries=2",
        "audit.retention.enabled=false"
})
@Import(EnrichmentPipelineIntegrationTest.Stubs.class)
class EnrichmentPipelineIntegrationTest {

    private static final String TOPIC = "audit-events";
    private static final String DLT = "audit-events.DLT";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auditflow").withUsername("auditflow").withPassword("auditflow");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * A sink that fails on demand, ordered between S3 (10) and Aurora (20)
     * so a failure leaves the evidence written and the queryable row not -
     * exactly the partial state a retry has to be safe against.
     */
    @Order(15)
    static class FlakySink implements DataSink {

        private final AtomicInteger attempts = new AtomicInteger();
        private volatile int failuresRemaining;

        void failNext(int times) {
            failuresRemaining = times;
            attempts.set(0);
        }

        int attempts() {
            return attempts.get();
        }

        @Override
        public void write(AuditEvent event) {
            attempts.incrementAndGet();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IllegalStateException("sink is unavailable (test)");
            }
        }

        @Override
        public void writeBatch(List<AuditEvent> events) {
            events.forEach(this::write);
        }

        @Override
        public String sinkName() {
            return "flaky";
        }
    }

    @TestConfiguration
    static class Stubs {

        // A different bean name from S3ClientConfig's, so this adds a bean
        // rather than trying to override one; @Primary is what makes the
        // adapter take it. The real bean is still constructed, which is
        // harmless - building an S3Client opens no connection.
        @Bean
        @Primary
        S3Client stubS3Client() {
            // the AWS SDK's own interface, stubbed at the boundary; the
            // adapter ignores the response, so a no-op mock is enough
            return mock(S3Client.class);
        }

        @Bean
        FlakySink flakySink() {
            return new FlakySink();
        }
    }

    @Autowired
    private FlakySink flakySink;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, AuditEvent> enrichedEventKafkaTemplate;

    @BeforeEach
    void reset() {
        flakySink.failNext(0);
        jdbc.update("DELETE FROM audit_events");
    }

    @Test
    void aFailingSinkIsRetriedUntilItRecoversAndNothingIsDeadLettered() {
        flakySink.failNext(2);

        publish(event("evt-recovers"));

        waitFor("the row to land", () -> rows("evt-recovers") == 1);
        // two failures then a success: the record was redelivered, not dropped
        assertThat(flakySink.attempts()).isEqualTo(3);
        // the DLT is shared with the other tests, so ask about this event
        assertThat(deadLetteredContaining("evt-recovers")).isEmpty();
    }

    @Test
    void aSinkThatNeverRecoversSendsTheEventToTheDeadLetterTopic() {
        flakySink.failNext(Integer.MAX_VALUE);

        publish(event("evt-never"));

        List<ConsumerRecord<byte[], byte[]>> dead = waitForDeadLetter("evt-never");
        assertThat(dead).hasSize(1);
        // the original attempt plus max-retries
        assertThat(flakySink.attempts()).isEqualTo(3);
        // and it is not in Aurora, which is the point of dead-lettering it
        assertThat(rows("evt-never")).isZero();
    }

    @Test
    void bytesThatAreNotAnEventGoStraightToTheDeadLetterTopic() {
        // A poison record cannot be fixed by retrying, so it should not be
        // retried - and it must reach the DLT as the original bytes, or the
        // thing worth investigating is lost on the way.
        try (KafkaProducer<String, byte[]> raw = rawProducer()) {
            raw.send(new ProducerRecord<>(TOPIC, "cust-1", "not json at all".getBytes(StandardCharsets.UTF_8)));
            raw.flush();
        }

        List<ConsumerRecord<byte[], byte[]>> dead = waitForDeadLetter("not json at all");
        assertThat(dead).hasSize(1);
        // the original bytes, unchanged - the whole reason the dead-letter
        // producer serializes byte[] rather than re-encoding as JSON
        assertThat(new String(dead.get(0).value(), StandardCharsets.UTF_8)).isEqualTo("not json at all");
        assertThat(header(dead.get(0), "kafka_dlt-exception-fqcn")).contains("DeserializationException");
        // and it was not retried first: a poison record cannot be fixed by
        // trying again, so the flaky sink never saw it
        assertThat(flakySink.attempts()).isZero();
    }

    // --- helpers -----------------------------------------------------------

    private static AuditEvent event(String eventId) {
        return AuditEvent.builder()
                .eventId(eventId)
                .customerId("cust-1")
                .type(EventType.DATABASE_QUERY)
                .resource("customers_table")
                .action("SELECT")
                .timestamp(Instant.now())
                .build();
    }

    private void publish(AuditEvent event) {
        enrichedEventKafkaTemplate.send(TOPIC, event.getCustomerId(), event);
        enrichedEventKafkaTemplate.flush();
    }

    private Integer rows(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_id = ?", Integer.class, eventId);
    }

    private List<ConsumerRecord<byte[], byte[]>> waitForDeadLetter(String needle) {
        List<ConsumerRecord<byte[], byte[]>> found = new ArrayList<>();
        waitFor("a dead-lettered record containing '" + needle + "'", () -> {
            found.clear();
            found.addAll(deadLetteredContaining(needle));
            return !found.isEmpty();
        });
        return found;
    }

    /**
     * All three tests share one dead-letter topic and JUnit does not promise
     * an order, so every assertion is scoped to its own event rather than to
     * the topic being empty.
     */
    private List<ConsumerRecord<byte[], byte[]>> deadLetteredContaining(String needle) {
        return deadLettered().stream()
                .filter(record -> new String(record.value(), StandardCharsets.UTF_8).contains(needle))
                .toList();
    }

    private List<ConsumerRecord<byte[], byte[]>> deadLettered() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(DLT));
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(2));
            List<ConsumerRecord<byte[], byte[]>> result = new ArrayList<>();
            records.records(DLT).forEach(result::add);
            return result;
        }
    }

    private static KafkaProducer<String, byte[]> rawProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Small poll loop rather than a new test dependency. */
    private static void waitFor(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }
}
