package com.auditflow.ingestion;

import com.auditflow.common.enums.EventType;
import com.auditflow.ingestion.api.IngestEventRequest;
import com.auditflow.ingestion.security.IngestTokenFilter;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.auditflow.common.model.AuditEvent;
import org.springframework.kafka.support.JacksonUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that a real audit event, posted over HTTP, actually lands
 * on the Kafka topic that enrichment-service consumes from. Uses a real
 * Kafka broker via Testcontainers rather than mocking KafkaTemplate, per the
 * "no mocking internal components" testing principle.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "audit.ingestion.tokens=cust-1=tok-1,cust-2=tok-2")
class EventIngestionIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void postedEventIsPublishedToKafka() {
        IngestEventRequest request = new IngestEventRequest(
                "evt-int-1", "cust-1", "user-1", "sess-1",
                EventType.DATABASE_QUERY, "customers_table", "SELECT",
                "SELECT * FROM customers", "127.0.0.1", "test-agent", "raw-log-line", null);

        HttpHeaders headers = new HttpHeaders();
        headers.set(IngestTokenFilter.TOKEN_HEADER, "tok-1");
        ResponseEntity<Void> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(request, headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        List<ConsumerRecord<String, String>> records = consumeFromTopic("audit-events", Duration.ofSeconds(10));
        assertThat(records).anySatisfy(record -> assertThat(record.value()).contains("evt-int-1"));
    }

    /**
     * The source's event time has to survive the whole path, not just the
     * controller: serialised into the request, bound back to an Instant,
     * carried onto the AuditEvent, and serialised again onto the Kafka
     * record that the rest of the pipeline reads.
     */
    @Test
    void theSourcesEventTimeSurvivesOntoTheKafkaRecord() throws Exception {
        Instant sourceTime = Instant.parse("2026-01-02T03:04:05Z");
        IngestEventRequest request = new IngestEventRequest(
                "evt-int-occurred", "cust-1", "user-1", "sess-1",
                EventType.DATABASE_QUERY, "customers_table", "SELECT",
                "SELECT * FROM customers", "127.0.0.1", "test-agent", "raw-log-line", sourceTime);

        HttpHeaders headers = new HttpHeaders();
        headers.set(IngestTokenFilter.TOKEN_HEADER, "tok-1");
        ResponseEntity<Void> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(request, headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ConsumerRecord<String, String> record =
                consumeUntil("audit-events", "evt-int-occurred", Duration.ofSeconds(20));

        // Read the record back the way enrichment-service does, rather than
        // string-matching the JSON: how spring-kafka's serializer renders an
        // Instant is its business, and asserting on that would pin an
        // implementation detail instead of the property that matters - the
        // source's event time is what came out the other end.
        AuditEvent published = JacksonUtils.enhancedObjectMapper()
                .readValue(record.value(), AuditEvent.class);
        assertThat(published.getTimestamp()).isEqualTo(sourceTime);
    }

    @Test
    void topicIsDeclaredWithTheConfiguredRetention() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            ConfigResource topic = new ConfigResource(ConfigResource.Type.TOPIC, "audit-events");
            Config config = admin.describeConfigs(List.of(topic)).all().get(10, TimeUnit.SECONDS).get(topic);
            assertThat(config.get("retention.ms").value())
                    .isEqualTo(String.valueOf(Duration.ofDays(7).toMillis()));
        }
    }

    @Test
    void aTokenMayOnlyPostEventsForItsOwnCustomer() {
        IngestEventRequest asAnotherCustomer = new IngestEventRequest(
                "evt-int-forged", "cust-2", "user-1", "sess-1",
                EventType.DATABASE_QUERY, "customers_table", "SELECT",
                "SELECT * FROM customers", "127.0.0.1", "test-agent", "raw-log-line", null);

        HttpHeaders headers = new HttpHeaders();
        headers.set(IngestTokenFilter.TOKEN_HEADER, "tok-1"); // bound to cust-1
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(asAnotherCustomer, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // and nothing reached the topic
        List<ConsumerRecord<String, String>> records = consumeFromTopic("audit-events", Duration.ofSeconds(5));
        assertThat(records).noneSatisfy(record ->
                assertThat(record.value()).contains("evt-int-forged"));
    }

    @Test
    void noTokenIsRejectedBeforeAnythingIsPublished() throws Exception {
        // Deliberately the JDK client rather than TestRestTemplate: on a 401
        // HttpURLConnection tries to re-send the request with credentials and
        // throws HttpRetryException instead of returning the status, because
        // the body was already streamed.
        String body = """
                {"eventId":"evt-int-anon","customerId":"cust-1","type":"API_CALL","action":"a"}
                """;
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://localhost:" + port + "/api/v1/events"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /**
     * Polls until a record whose value contains {@code needle} shows up, or
     * the deadline passes. A single poll() is not enough: it returns as soon
     * as the broker hands back anything at all, so with more than one record
     * on the topic the one being looked for can easily be in a later batch.
     */
    private ConsumerRecord<String, String> consumeUntil(String topic, String needle, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1)).records(topic)) {
                    if (record.value().contains(needle)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record containing '" + needle + "' on " + topic
                + " within " + timeout);
    }

    private List<ConsumerRecord<String, String>> consumeFromTopic(String topic, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = consumer.poll(timeout);
            List<ConsumerRecord<String, String>> result = new ArrayList<>();
            records.records(topic).forEach(result::add);
            return result;
        }
    }
}
