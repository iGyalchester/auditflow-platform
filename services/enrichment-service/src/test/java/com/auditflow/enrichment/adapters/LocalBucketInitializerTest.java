package com.auditflow.enrichment.adapters;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalBucketInitializerTest {

    private final S3Client s3 = mock(S3Client.class);

    @Test
    void createsTheBucketWhenLocalStackDoesNotHaveIt() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().build());

        new LocalBucketInitializer(s3, "auditflow-events", "http://localstack:4566").ensureBucket();

        verify(s3).createBucket(CreateBucketRequest.builder().bucket("auditflow-events").build());
    }

    @Test
    void leavesAnExistingBucketAlone() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        new LocalBucketInitializer(s3, "auditflow-events", "http://localstack:4566").ensureBucket();

        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void anUnreachableLocalEndpointIsAWarningNotAStartupFailure() {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Connection refused"));

        assertThatCode(() -> new LocalBucketInitializer(s3, "auditflow-events", "http://localhost:4566").ensureBucket())
                .doesNotThrowAnyException();

        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void neverTouchesRealAws() {
        LocalBucketInitializer initializer = new LocalBucketInitializer(s3, "auditflow-events", "");

        initializer.ensureBucket();

        verify(s3, never()).headBucket(any(HeadBucketRequest.class));
        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }
}
