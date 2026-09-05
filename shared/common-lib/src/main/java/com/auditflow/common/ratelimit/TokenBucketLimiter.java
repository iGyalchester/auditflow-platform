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
 * shared store on the request path.
 *
 * <p>Bounded at {@code maxKeys}. When the table is full, idle buckets are
 * swept (at most once a second, so a flood of new keys cannot turn every
 * request into a full scan) and a key that still does not fit is simply
 * refused. It deliberately does <em>not</em> clear the table: dropping every
 * bucket hands each tracked client a fresh, full allowance, which is the
 * outcome an attacker cycling keys is trying to buy. Refusing the unknown
 * key costs one stranger a retry; wiping costs everyone their limit.
 */
public class TokenBucketLimiter {

    /** The answer for one request: allowed or not, what is left, and when to retry if not. */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private static final long IDLE_EVICTION_NANOS = 5L * 60 * 1_000_000_000L;
    private static final long SWEEP_INTERVAL_NANOS = 1_000_000_000L;

    /** What a refused new key is told to wait when the table is full of active clients. */
    private static final long FULL_TABLE_RETRY_AFTER_SECONDS = 5;

    private final long capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile long lastSweepNanos;

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
        // so the first full table sweeps immediately rather than waiting
        this.lastSweepNanos = nanoClock.getAsLong() - SWEEP_INTERVAL_NANOS;
    }

    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                sweepIdle(now);
                if (buckets.size() >= maxKeys) {
                    // Every slot belongs to a client seen in the last five
                    // minutes. Refuse the newcomer rather than evict them.
                    return new Decision(false, 0, FULL_TABLE_RETRY_AFTER_SECONDS);
                }
            }
            // Racing threads can both pass the check above, so the table can
            // briefly hold a few more than maxKeys - bounded by concurrency,
            // not by the attacker.
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        }
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

    /**
     * Drops buckets nobody has used for {@link #IDLE_EVICTION_NANOS}. Rate
     * limited to once a second: while the table is full every new key would
     * otherwise trigger a full scan, turning the flood into CPU cost.
     */
    private void sweepIdle(long now) {
        if (now - lastSweepNanos < SWEEP_INTERVAL_NANOS) {
            return;
        }
        synchronized (this) {
            if (now - lastSweepNanos < SWEEP_INTERVAL_NANOS) {
                return;
            }
            lastSweepNanos = now;
            Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().lastSeen > IDLE_EVICTION_NANOS) {
                    it.remove();
                }
            }
        }
    }

    int trackedKeys() {
        return buckets.size();
    }

    private final class Bucket {
        double tokens;
        long lastRefill;
        // read by the sweep without holding the bucket's lock
        volatile long lastSeen;

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
