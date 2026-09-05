package com.auditflow.common.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

import java.util.HashMap;
import java.util.Map;

/**
 * MSK Serverless speaks SASL/IAM: the broker authenticates the caller's AWS
 * identity (on ECS, the task role) instead of a username and password.
 * Shared by every consumer, producer and admin client, and enabled by the
 * "aws" profile through {@code audit.kafka.msk-iam=true}.
 *
 * <p>Lives in common-lib because ingestion and enrichment each had an
 * identical private copy. Four exact string constants that must agree with
 * the broker is not the kind of thing to keep two of: a fix to one is a
 * silent divergence in the other, and the symptom is an authentication
 * failure at connect time in whichever service was not updated.
 */
public final class MskIam {

    private MskIam() {
    }

    public static Map<String, Object> clientProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
        props.put(SaslConfigs.SASL_JAAS_CONFIG, "software.amazon.msk.auth.iam.IAMLoginModule required;");
        props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        return props;
    }
}
