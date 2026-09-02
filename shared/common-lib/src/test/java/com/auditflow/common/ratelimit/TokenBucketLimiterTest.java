package com.auditflow.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketLimiterTest {

    private final AtomicLong nanos = new AtomicLong(0);

    private TokenBucketLimiter limiter(long capacity, double perSecond, int maxKeys) {
        return new TokenBucketLimiter(capacity, perSecond, maxKeys, nanos::get);
    }

    @Test
    void allowsTheBurstThenDeniesWithARetryAfter() {
        TokenBucketLimiter limiter = limiter(3, 1, 100);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        TokenBucketLimiter.Decision last = limiter.tryAcquire("a");
        assertThat(last.allowed()).isTrue();
        assertThat(last.remaining()).isZero();

        TokenBucketLimiter.Decision denied = limiter.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTimeUpToCapacity() {
        TokenBucketLimiter limiter = limiter(2, 2, 100);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        nanos.addAndGet(500_000_000L); // 0.5 s at 2/s = 1 token
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        nanos.addAndGet(60_000_000_000L); // a minute: capped at capacity, not 120 tokens
        assertThat(limiter.tryAcquire("a").remaining()).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        TokenBucketLimiter limiter = limiter(1, 1, 100);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void boundedKeysEvictIdleBucketsFirstThenResetIfAllActive() {
        TokenBucketLimiter limiter = limiter(1, 1, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        nanos.addAndGet(6L * 60 * 1_000_000_000L); // both idle > 5 min
        limiter.tryAcquire("c");
        assertThat(limiter.trackedKeys()).isEqualTo(1);

        limiter.tryAcquire("d");                    // table full again, all active
        limiter.tryAcquire("e");                    // forces a reset
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new TokenBucketLimiter(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter(1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
