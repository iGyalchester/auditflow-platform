package com.auditflow.enrichment.adapters;

import com.auditflow.common.interfaces.DataSink;
import com.auditflow.common.model.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;

/**
 * Writes audit events to the immutable, versioned S3 evidence store.
 * Implements {@link DataSink} so enrichment logic has zero dependency on the
 * concrete storage technology - swapping this for another object store is a
 * new DataSink implementation, not a rewrite.
 */
@Component
public class S3WriterAdapter implements DataSink {

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String bucket;

    public S3WriterAdapter(S3Client s3Client,
                            ObjectMapper objectMapper,
                            @Value("${audit.storage.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
    }

    @Override
    public void write(AuditEvent event) {
        try {
            String key = "%s/%s.json".formatted(event.getCustomerId(), event.getEventId());
            byte[] body = objectMapper.writeValueAsBytes(event);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write audit event to S3", e);
        }
    }

    @Override
    public void writeBatch(List<AuditEvent> events) {
        events.forEach(this::write);
    }

    @Override
    public String sinkName() {
        return "s3";
    }
}
