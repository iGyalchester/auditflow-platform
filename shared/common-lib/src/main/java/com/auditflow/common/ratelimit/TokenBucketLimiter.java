package com.auditflow.common.ratelimit;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key token buckets: every key may burst up to {@code capacity}
 * requests, then sustain {@code refillPerSecond}. In-memory and per
 * instance on purpose - with N instances behind a load balancer the
 * effective limit is N times the configured one, which is fine for what
 * this protects against (one client hammering one endpoint) and avoids a
 * shared store on the request path. Bounded: past {@code maxKeys} idle
 * buckets are evicted, so an attacker cycling keys cannot grow memory.
 */
public class TokenBucketLimiter {

    /** The answer for one request: allowed or not, what is left, and when to retry if not. */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private static final long IDLE_EVICTION_NANOS = 5L * 60 * 1_000_000_000L;

    private final long capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(long capacity, double refillPerSecond, int maxKeys) {
        this(capacity, refillPerSecond, maxKeys, System::nanoTime);
    }

    TokenBucketLimiter(long capacity, double refillPerSecond, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0 || maxKeys < 1) {
            throw new IllegalArgumentException("capacity >= 1, refillPerSecond > 0 and maxKeys >= 1 required");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        if (buckets.size() >= maxKeys && !buckets.containsKey(key)) {
            evictIdle(now);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            bucket.refill(now);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return new Decision(true, (long) bucket.tokens, 0);
            }
            double deficit = 1 - bucket.tokens;
            long retryAfter = (long) Math.ceil(deficit / refillPerSecond);
            return new Decision(false, 0, Math.max(1, retryAfter));
        }
    }

    private void evictIdle(long now) {
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> entry = it.next();
            if (now - entry.getValue().lastSeen > IDLE_EVICTION_NANOS) {
                it.remove();
            }
        }
        if (buckets.size() >= maxKeys) {
            // Everyone is active: drop the table rather than the request path. A
            // burst of fresh buckets for real clients is a far smaller harm than
            // unbounded growth.
            buckets.clear();
        }
    }

    int trackedKeys() {
        return buckets.size();
    }

    private final class Bucket {
        double tokens;
        long lastRefill;
        long lastSeen;

        Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
            this.lastSeen = now;
        }

        void refill(long now) {
            double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
                lastRefill = now;
            }
            lastSeen = now;
        }
    }
}
