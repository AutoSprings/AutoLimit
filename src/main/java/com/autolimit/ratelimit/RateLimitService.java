package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Per-process fixed-window quotas. Active counters expire at their window boundary,
 * never on idle time or capacity pressure. At capacity, new keys are denied until
 * an expired key releases a slot; existing keys continue to use their quotas.
 * This service is not a token revocation store.
 */
public class RateLimitService {

    private record Window(long count, long endsAtNanos) {}
    private final Ticker ticker;

    private final Cache<String, Window> cache;

    private static final long MAX_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(365);

    private final int maxTrackedKeys;
    private final Object admissionLock = new Object();
    // Guarded by admissionLock; cache size can include entries awaiting expiration maintenance.
    private final Set<String> trackedKeys = new HashSet<>();

    /** Creates a limiter with capacity for 100,000 distinct quota keys. */
    public RateLimitService() {
        this(100_000);
    }

    /**
     * Creates a limiter with a positive capacity shared by all quota keys.
     * @param maxTrackedKeys maximum number of distinct tracked keys
     */
    public RateLimitService(int maxTrackedKeys) {
        this(Ticker.systemTicker(), maxTrackedKeys);
    }

    RateLimitService(Ticker ticker, int maxTrackedKeys) {
        this.ticker = Objects.requireNonNull(ticker);

        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be greater than 0");
        }

        this.maxTrackedKeys = maxTrackedKeys;

        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfter(new Expiry<String, Window>() {
                    @Override
                    public long expireAfterCreate(
                            String key, Window value, long currentTime
                    ) {
                        return remaining(value, currentTime);
                    }

                    @Override
                    public long expireAfterUpdate(
                            String key, Window value,
                            long currentTime, long currentDuration
                    ) {
                        return remaining(value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(
                            String key, Window value,
                            long currentTime, long currentDuration
                    ) {
                        return currentDuration;
                    }
                })
                .build();
    }

    private static long remaining(Window window, long now) {
        return Math.max(0L, window.endsAtNanos() - now);
    }

    /**
     * Atomically consumes one request from the key's fixed window.
     * @param key quota identity; callers must use consistent window settings per key
     * @param maxRequests nonnegative quota; zero always denies without allocating state
     * @param windowMillis window length from 1 millisecond through 365 days
     * @return whether the request was admitted; false also indicates capacity exhaustion
     * @throws IllegalArgumentException for a negative quota or unsupported window
     * @throws NullPointerException if key is null
     */
    public boolean isAllowed(String key, int maxRequests, long windowMillis) {

        Objects.requireNonNull(key, "key");

        if (maxRequests < 0) {

            throw new IllegalArgumentException("maxRequests cant be negative");
        }

        if (windowMillis <= 0 || windowMillis > MAX_WINDOW_MILLIS) {

            throw new IllegalArgumentException("windowMillis must be between 1 ms and 365 days");
        }

        if (maxRequests == 0) {return false;}

        long windowNanos = Math.multiplyExact(windowMillis, 1_000_000L);

        boolean[] allowed = {false};

        // Existing keys retain per-key atomic updates without taking the admission lock.
        Window updated = cache.asMap().computeIfPresent(key,
                (k, existing) -> consume(existing, maxRequests, windowNanos, allowed));
        if (updated != null) {
            return allowed[0];
        }

        // All insertions share this lock so distinct keys cannot claim the same free slot.
        synchronized (admissionLock) {
            if (!trackedKeys.contains(key) && trackedKeys.size() >= maxTrackedKeys) {
                // Coordinate expiry checks with quota updates for each key. A separate
                // read followed by invalidate could delete a concurrently renewed window.
                trackedKeys.removeIf(trackedKey -> cache.asMap().computeIfPresent(trackedKey,
                        (k, current) -> remaining(current, ticker.read()) > 0 ? current : null) == null);
                if (trackedKeys.size() >= maxTrackedKeys) {
                    return false;
                }
            }

            cache.asMap().compute(key,
                    (k, existing) -> consume(existing, maxRequests, windowNanos, allowed));
            trackedKeys.add(key);
            return allowed[0];
        }
    }

    private Window consume(Window current, int maxRequests, long windowNanos, boolean[] allowed) {
        long now = ticker.read();
        if (current == null || now - current.endsAtNanos() >= 0) {
            current = new Window(0, now + windowNanos);
        }

        if (current.count() >= maxRequests) {
            return current;
        }

        allowed[0] = true;
        return new Window(current.count() + 1, current.endsAtNanos());
    }
}
