package com.auditflow.enrichment.adapters;

import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Producer for the enriched topic that alerting-service consumes. Same
 * durability stance as ingestion's producer: acks=all + idempotence, so
 * the ack {@link EnrichedTopicSink} waits for means the record is
 * replicated. The topic is declared here with its retention, since MSK
 * Serverless retention is a per-topic setting.
 */
@Configuration
public class EnrichedKafkaProducerConfig {

    @Bean
    public ProducerFactory<String, AuditEvent> enrichedEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${audit.kafka.msk-iam:false}") boolean mskIamAuth,
            @Value("${audit.enrichment.delivery-timeout:10s}") Duration deliveryTimeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeout.toMillis());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(deliveryTimeout.toMillis(), 5_000));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, deliveryTimeout.toMillis());
        if (mskIamAuth) {
            props.putAll(MskIam.clientProperties());
        }
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, AuditEvent> enrichedEventKafkaTemplate(
            ProducerFactory<String, AuditEvent> enrichedEventProducerFactory) {
        return new KafkaTemplate<>(enrichedEventProducerFactory);
    }

    @Bean
    public KafkaAdmin enrichmentKafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                           @Value("${audit.kafka.msk-iam:false}") boolean mskIamAuth) {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        if (mskIamAuth) {
            props.putAll(MskIam.clientProperties());
        }
        KafkaAdmin admin = new KafkaAdmin(props);
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }

    @Bean
    public NewTopic enrichedEventsTopic(@Value("${audit.enrichment.enriched-topic:audit-events-enriched}") String topic,
                                        @Value("${audit.enrichment.enriched-topic-partitions:6}") int partitions,
                                        @Value("${audit.enrichment.enriched-topic-retention:7d}") Duration retention) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                .build();
    }
}
