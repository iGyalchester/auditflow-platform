package com.auditflow.enrichment.adapters;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Points at LocalStack for local dev (see docker-compose.yml); in a real
 * AWS environment the endpoint override is simply omitted and the default
 * credentials provider chain / IAM role takes over.
 */
@Configuration
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(@Value("${audit.storage.s3.endpoint}") String endpoint,
                              @Value("${audit.storage.s3.region}") String region) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
