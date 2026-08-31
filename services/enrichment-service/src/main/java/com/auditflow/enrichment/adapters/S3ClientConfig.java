package com.auditflow.enrichment.adapters;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Local dev (an endpoint override is configured, see docker-compose.yml):
 * points at LocalStack with its dummy static credentials. Real AWS (the
 * "aws" profile blanks the endpoint): the default client - regional
 * endpoint plus the default credentials provider chain, which on ECS
 * resolves to the task role.
 */
@Configuration
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(@Value("${audit.storage.s3.endpoint:}") String endpoint,
                              @Value("${audit.storage.s3.region}") String region) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }
}
