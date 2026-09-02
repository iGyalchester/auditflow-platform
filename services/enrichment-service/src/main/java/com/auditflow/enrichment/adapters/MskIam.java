package com.auditflow.enrichment.adapters;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

import java.util.HashMap;
import java.util.Map;

/**
 * MSK Serverless speaks SASL/IAM: the broker authenticates the caller's AWS
 * identity (on ECS, the task role) instead of a username/password. Shared by
 * the consumer, producer and admin clients; enabled by the "aws" profile via
 * audit.kafka.msk-iam=true.
 */
final class MskIam {
    private MskIam() {
    }

    static Map<String, Object> clientProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
        props.put(SaslConfigs.SASL_JAAS_CONFIG, "software.amazon.msk.auth.iam.IAMLoginModule required;");
        props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        return props;
    }
}
