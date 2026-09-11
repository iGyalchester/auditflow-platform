package com.auditflow.alerting.adapters;

import com.auditflow.common.model.AuditEvent;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/** Consumer of enriched events; mirrors enrichment-service's consumer setup. */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public ConsumerFactory<String, AuditEvent> enrichedEventConsumerFactory(
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
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
            props.put(SaslConfigs.SASL_JAAS_CONFIG, "software.amazon.msk.auth.iam.IAMLoginModule required;");
            props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Deliberately no dead-letter topic here, unlike enrichment.
     *
     * <p>Alerting reads events that are already stored: S3 has the evidence
     * and Aurora has the queryable row before anything is republished to
     * this topic. A record that cannot be matched costs a missed alert, not
     * a lost audit trail, so there is nothing to preserve for replay - and
     * replaying it later would page someone about something long past.
     *
     * <p>AlertDispatcher is contracted never to throw (one failing channel
     * never blocks another), so reaching this handler means something
     * unexpected. Two quick retries, then log loudly and move on rather
     * than let one bad record stall the partition and delay every alert
     * behind it.
     */
    @Bean
    public DefaultErrorHandler enrichedEventErrorHandler() {
        return new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Giving up on enriched event {}-{}@{}; no alert was raised for it: {}",
                        record.topic(), record.partition(), record.offset(), exception.toString()),
                new FixedBackOff(1000L, 2L));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, AuditEvent> enrichedEventConsumerFactory,
            DefaultErrorHandler enrichedEventErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, AuditEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(enrichedEventConsumerFactory);
        factory.setCommonErrorHandler(enrichedEventErrorHandler);
        return factory;
    }
}
