package com.auditflow.enrichment.adapters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Makes sure the evidence bucket exists before the first event is
 * consumed - in local runs only. The S3 sink writes first and a missing
 * bucket dead-letters every event after ~40 seconds of retries, with
 * nothing in Postgres and nothing on the gateway; that is exactly how a
 * LocalStack whose ready hook did not run (CRLF checkout, a bind mount
 * without the execute bit) loses a whole demo silently.
 *
 * <p>Gated on the endpoint override: it is set only for LocalStack (see
 * {@link S3ClientConfig}); on AWS the endpoint is blank, the bucket is
 * Terraform's with Object Lock and versioning, and this class does
 * nothing.
 */
@Component
public class LocalBucketInitializer {

    private static final Logger log = LoggerFactory.getLogger(LocalBucketInitializer.class);

    private final S3Client s3Client;
    private final String bucket;
    private final boolean local;

    public LocalBucketInitializer(S3Client s3Client,
                                  @Value("${audit.storage.s3.bucket}") String bucket,
                                  @Value("${audit.storage.s3.endpoint:}") String endpoint) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.local = endpoint != null && !endpoint.isBlank();
    }

    /**
     * Never lets the app fail to start: an unreachable local endpoint (the
     * integration tests run without LocalStack, and so does a plain
     * spring-boot:run against infrastructure that is still coming up) is
     * a warning here and a clear error from the sink later, not a crash.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        if (!local) {
            return;
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Evidence bucket {} present on the local S3 endpoint", bucket);
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.warn("Evidence bucket {} was missing on the local S3 endpoint; created it", bucket);
            } catch (RuntimeException create) {
                log.warn("Evidence bucket {} is missing and could not be created: {}", bucket, create.toString());
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the evidence bucket on the local S3 endpoint: {}", e.toString());
        }
    }

    boolean isLocal() {
        return local;
    }
}
