package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Per-process fixed-window quotas. Active counters expire at their window boundary,
 * never on idle time or capacity pressure. At capacity, new keys are denied until
 * an expired key releases a slot; existing keys continue to use their quotas.
 * This service is not a token revocation store.
 * Windows start on first admission, do not slide, and permit bursts across their
 * boundaries. Counters are local to this service instance; admission is charged
 * before downstream work and is never refunded for a later failure.
 */
public class RateLimitService {

    private record Window(long count, long endsAtNanos) {}
    private record Deadline(String key, long endsAtNanos) {}
    private final Ticker ticker;

    private final Cache<String, Window> cache;

    private static final long MAX_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(365);

    private final int maxTrackedKeys;
    private final Object admissionLock = new Object();
    // Guarded by admissionLock; cache size can include entries awaiting expiration maintenance.
    private final Set<String> trackedKeys = new HashSet<>();
    // One entry per tracked key, guarded by admissionLock. A renewed window can
    // leave an earlier deadline here; reclamation refreshes it atomically.
    private final PriorityQueue<Deadline> deadlines;

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
        long clockOrigin = ticker.read();
        // Normalize to the service's clock origin so crossing nanoTime's signed
        // boundary does not reorder deadlines within the supported window range.
        this.deadlines = new PriorityQueue<>(Comparator.comparingLong(
                deadline -> deadline.endsAtNanos() - clockOrigin));

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
        return check(key, maxRequests, windowMillis).allowed();
    }

    /**
     * Atomically checks and consumes quota, capturing retry timing in the same update.
     * This is the backend extension point used by the aspect and IP filter.
     *
     * @param key quota identity; use consistent window settings per key
     * @param maxRequests nonnegative quota; zero denies without allocating state
     * @param windowMillis window length from 1 millisecond through 365 days
     * @return admission result; exhausted windows report remaining time rounded up
     *         to seconds, while zero quotas and capacity rejections have no retry delay
     * @throws IllegalArgumentException for a negative quota or unsupported window
     * @throws NullPointerException if key is null
     */
    public RateLimitDecision check(String key, int maxRequests, long windowMillis) {

        Objects.requireNonNull(key, "key");

        if (maxRequests < 0) {

            throw new IllegalArgumentException("maxRequests cant be negative");
        }

        if (windowMillis <= 0 || windowMillis > MAX_WINDOW_MILLIS) {

            throw new IllegalArgumentException("windowMillis must be between 1 ms and 365 days");
        }

        if (maxRequests == 0) {return RateLimitDecision.deny();}

        long windowNanos = Math.multiplyExact(windowMillis, 1_000_000L);

        RateLimitDecision[] decision = new RateLimitDecision[1];

        // Existing keys retain per-key atomic updates without taking the admission lock.
        Window updated = cache.asMap().computeIfPresent(key,
                (k, existing) -> consume(existing, maxRequests, windowNanos, decision));
        if (updated != null) {
            return decision[0];
        }

        // All insertions share this lock so distinct keys cannot claim the same free slot.
        synchronized (admissionLock) {
            if (!trackedKeys.contains(key) && trackedKeys.size() >= maxTrackedKeys) {
                reclaimExpiredSlot();
                if (trackedKeys.size() >= maxTrackedKeys) {
                    return RateLimitDecision.deny();
                }
            }

            Window window = cache.asMap().compute(key,
                    (k, existing) -> consume(existing, maxRequests, windowNanos, decision));
            if (trackedKeys.add(key)) {
                deadlines.add(new Deadline(key, window.endsAtNanos()));
            }
            return decision[0];
        }
    }

    // Called only under admissionLock. Stop as soon as a slot is available;
    // when every indexed deadline is in the future, denial is constant work.
    private void reclaimExpiredSlot() {
        while (trackedKeys.size() >= maxTrackedKeys && !deadlines.isEmpty()
                && ticker.read() - deadlines.peek().endsAtNanos() >= 0) {
            Deadline expired = deadlines.remove();
            // The per-key update coordinates with concurrent admissions/renewals.
            Window current = cache.asMap().computeIfPresent(expired.key(),
                    (key, window) -> remaining(window, ticker.read()) > 0 ? window : null);
            if (current == null) {
                trackedKeys.remove(expired.key());
            } else {
                deadlines.add(new Deadline(expired.key(), current.endsAtNanos()));
            }
        }
    }

    private Window consume(Window current, int maxRequests, long windowNanos, RateLimitDecision[] decision) {
        long now = ticker.read();
        if (current == null || now - current.endsAtNanos() >= 0) {
            current = new Window(0, now + windowNanos);
        }

        if (current.count() >= maxRequests) {
            long remainingNanos = current.endsAtNanos() - now;
            long seconds = 1 + (remainingNanos - 1) / TimeUnit.SECONDS.toNanos(1);
            decision[0] = RateLimitDecision.deny(seconds);
            return current;
        }

        decision[0] = RateLimitDecision.permit();
        return new Window(current.count() + 1, current.endsAtNanos());
    }
}
