package com.auditflow.enrichment.adapters;

import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

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
            // MSK Serverless SASL/IAM: broker authenticates the AWS identity
            // (the ECS task role) - enabled by the "aws" profile.
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
            props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    "software.amazon.msk.auth.iam.IAMLoginModule required;");
            props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, AuditEvent> auditEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, AuditEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(auditEventConsumerFactory);
        return factory;
    }
}
