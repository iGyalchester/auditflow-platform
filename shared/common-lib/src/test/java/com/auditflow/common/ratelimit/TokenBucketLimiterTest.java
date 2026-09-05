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
    void idleBucketsAreSweptToMakeRoom() {
        TokenBucketLimiter limiter = limiter(1, 1, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");

        nanos.addAndGet(6L * 60 * 1_000_000_000L); // both idle > 5 min

        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void aFullTableOfActiveClientsRefusesNewKeysInsteadOfWipingKnownOnes() {
        // The old behaviour cleared the table here, which handed every
        // tracked client a fresh full allowance - precisely what an attacker
        // cycling keys wants. Refusing the newcomer costs one stranger a
        // retry instead.
        // A slow refill on purpose: with a fast one the buckets would top
        // back up during the wait and a wiped bucket would be
        // indistinguishable from a kept one.
        TokenBucketLimiter limiter = limiter(5, 0.01, 2);
        limiter.tryAcquire("known-1");
        limiter.tryAcquire("known-2");

        nanos.addAndGet(2_000_000_000L); // past the sweep interval, still active

        TokenBucketLimiter.Decision refused = limiter.tryAcquire("stranger");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(5);
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        // Their second request leaves 3 of 5. A wiped table would have given
        // them fresh buckets and left 4 - which is the bug this pins.
        assertThat(limiter.tryAcquire("known-1").remaining()).isEqualTo(3);
        assertThat(limiter.tryAcquire("known-2").remaining()).isEqualTo(3);
    }

    @Test
    void theSweepRunsAtMostOncePerSecondSoAFloodCannotForceRepeatedScans() {
        TokenBucketLimiter limiter = limiter(1, 1, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");

        nanos.addAndGet(6L * 60 * 1_000_000_000L); // both now idle
        limiter.tryAcquire("c");                   // sweeps, drops a and b
        assertThat(limiter.trackedKeys()).isEqualTo(1);

        limiter.tryAcquire("d");                   // table has room, no sweep needed
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        // c and d are active, and the clock has not advanced, so the next
        // newcomer cannot trigger another sweep and is refused
        assertThat(limiter.tryAcquire("e").allowed()).isFalse();
        assertThat(limiter.trackedKeys()).isEqualTo(2);
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new TokenBucketLimiter(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter(1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
