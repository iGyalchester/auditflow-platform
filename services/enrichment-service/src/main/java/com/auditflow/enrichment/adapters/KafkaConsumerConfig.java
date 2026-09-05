package com.auditflow.enrichment.adapters;

import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Consuming raw events, and what happens when handling one fails.
 *
 * <p>With no error handler configured, Spring's default retried a record
 * nine more times with no pause between attempts and then <b>logged and
 * moved on</b>. Both halves are wrong here. Ten immediate attempts are over
 * in milliseconds, so the ordinary reason a sink throws - S3 or Aurora
 * briefly unreachable - is not survived at all. And when they run out the
 * event is gone: the offset is committed, nothing was written to S3, to
 * Aurora, or to the enriched topic, and the only trace is a log line. For a
 * system whose product is a complete audit trail, "dropped it, logged it"
 * is the wrong answer.
 *
 * <p>So: back off exponentially over roughly 40 seconds, which covers a
 * restart or a short outage while staying well inside the five-minute
 * {@code max.poll.interval.ms} that would otherwise trigger a rebalance.
 * Then publish whatever still cannot be handled to a dead-letter topic,
 * where it can be inspected and replayed. Nothing is silently discarded.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public ConsumerFactory<String, AuditEvent> auditEventConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId,
            @Value("${audit.kafka.msk-iam:false}") boolean mskIamAuth) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.auditflow.common.model");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AuditEvent.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        if (mskIamAuth) {
            props.putAll(MskIam.clientProperties());
        }
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Writes to the dead-letter topic. Serializers are chosen per type
     * because a record that failed to <em>deserialize</em> arrives as raw
     * {@code byte[]} and must be forwarded unchanged - otherwise the bytes
     * worth investigating would be lost on the way to the topic that exists
     * to preserve them.
     */
    @Bean
    public ProducerFactory<Object, Object> deadLetterProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${audit.kafka.msk-iam:false}") boolean mskIamAuth,
            @Value("${audit.enrichment.delivery-timeout:10s}") Duration deliveryTimeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeout.toMillis());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(deliveryTimeout.toMillis(), 5_000));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, deliveryTimeout.toMillis());
        if (mskIamAuth) {
            props.putAll(MskIam.clientProperties());
        }

        Map<Class<?>, Serializer<?>> keySerializers = Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer());
        Map<Class<?>, Serializer<?>> valueSerializers = Map.of(
                byte[].class, new ByteArraySerializer(),
                AuditEvent.class, new JsonSerializer<AuditEvent>());

        return new DefaultKafkaProducerFactory<>(props,
                new DelegatingByTypeSerializer(keySerializers),
                new DelegatingByTypeSerializer(valueSerializers));
    }

    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            ProducerFactory<Object, Object> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean
    public NewTopic deadLetterTopic(
            @Value("${audit.enrichment.dead-letter-topic:audit-events.DLT}") String topic,
            @Value("${audit.enrichment.dead-letter-topic-partitions:6}") int partitions,
            @Value("${audit.enrichment.dead-letter-topic-retention:7d}") Duration retention) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                .build();
    }

    @Bean
    public DefaultErrorHandler auditEventErrorHandler(
            KafkaTemplate<Object, Object> deadLetterKafkaTemplate,
            @Value("${audit.enrichment.dead-letter-topic:audit-events.DLT}") String deadLetterTopic,
            @Value("${audit.enrichment.retry.initial-interval:500ms}") Duration initialInterval,
            @Value("${audit.enrichment.retry.multiplier:2.0}") double multiplier,
            @Value("${audit.enrichment.retry.max-interval:8s}") Duration maxInterval,
            @Value("${audit.enrichment.retry.max-retries:8}") int maxRetries) {

        // Same partition on the DLT as on the source topic, so per-key
        // ordering survives the trip and an operator can find the record.
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            // Dead-lettering is not routine: it means an audit event was not
            // enriched and not stored. Say so at ERROR, with enough to find
            // the record, before the recoverer publishes it.
            log.error("Dead-lettering {}-{}@{} to {} after retries: {}",
                    record.topic(), record.partition(), record.offset(), deadLetterTopic,
                    exception.toString());
            publisher.accept(record, exception);
        }, backOff(initialInterval, multiplier, maxInterval, maxRetries));

        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Retry {} for {}-{}@{}: {}", deliveryAttempt,
                        record.topic(), record.partition(), record.offset(), exception.toString()));
        return handler;
    }

    private static ExponentialBackOffWithMaxRetries backOff(Duration initialInterval, double multiplier,
                                                            Duration maxInterval, int maxRetries) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        return backOff;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, AuditEvent> auditEventConsumerFactory,
            DefaultErrorHandler auditEventErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, AuditEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(auditEventConsumerFactory);
        factory.setCommonErrorHandler(auditEventErrorHandler);
        return factory;
    }
}
