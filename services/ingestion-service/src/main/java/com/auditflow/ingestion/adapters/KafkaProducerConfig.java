package com.auditflow.ingestion.adapters;

import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
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

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, AuditEvent> auditEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${audit.kafka.msk-iam:false}") boolean mskIamAuth,
            @Value("${audit.ingestion.delivery-timeout:10s}") Duration deliveryTimeout) {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Durability settings that make the ack the adapter waits for mean
        // something: every in-sync replica has the record (acks=all), retries
        // cannot duplicate or reorder it (idempotence), and a send that cannot
        // complete fails within a bounded time instead of hanging the request.
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeout.toMillis());
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(deliveryTimeout.toMillis(), 5_000));
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, deliveryTimeout.toMillis());
        if (mskIamAuth) {
            configProps.putAll(MskIam.clientProperties());
        }
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Creates the ingestion topic with an explicit retention on startup.
     * MSK Serverless has no cluster-level retention knob - it is a per-topic
     * config - so declaring the topic here is how the retention policy is
     * actually applied (and kept in version control). Idempotent: an
     * existing topic is left as is. Kafka being unreachable at startup is
     * logged, not fatal, so a local run without a broker still boots.
     */
    @Bean
    public KafkaAdmin auditKafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
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
    public NewTopic auditEventsTopic(@Value("${audit.ingestion.topic:audit-events}") String topic,
                                     @Value("${audit.ingestion.topic-partitions:6}") int partitions,
                                     @Value("${audit.ingestion.topic-retention:7d}") Duration retention) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                .build();
    }

    /**
     * MSK Serverless speaks SASL/IAM: the broker authenticates the caller's
     * AWS identity (on ECS, the task role) instead of a username/password.
     * Enabled by the "aws" profile via audit.kafka.msk-iam=true.
     */
    static final class MskIam {
        private MskIam() {
        }

        static Map<String, Object> clientProperties() {
            Map<String, Object> props = new HashMap<>();
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
            props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    "software.amazon.msk.auth.iam.IAMLoginModule required;");
            props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
            return props;
        }
    }

    @Bean
    public KafkaTemplate<String, AuditEvent> auditEventKafkaTemplate(
            ProducerFactory<String, AuditEvent> auditEventProducerFactory) {
        return new KafkaTemplate<>(auditEventProducerFactory);
    }
}
