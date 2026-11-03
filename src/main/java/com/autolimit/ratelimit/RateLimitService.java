package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * In memory cache of rate limits using Caffeine
 */
public class RateLimitService {

    private record Window(long count, long endsAtNanos) {}
    private final Ticker ticker;

    private final Cache<String, Window> cache;

    private static final long MAX_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(365);

    private static final int DEFAULT_MAX_TRACKED_KEYS = 100000;

    private final int maxTrackedKeys;

    public RateLimitService() {
        this(Ticker.systemTicker(), DEFAULT_MAX_TRACKED_KEYS);
    }

    public RateLimitService(int maxTrackedKeys) {
        this(Ticker.systemTicker(), maxTrackedKeys);
    }

    RateLimitService(Ticker ticker) {
        this(ticker, DEFAULT_MAX_TRACKED_KEYS);
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

    // check if a user has requests in the cache, if they go over the max return false to throw 429
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

        if (cache.getIfPresent(key) == null) {
            if (cache.asMap().size() >= maxTrackedKeys) {
                cache.cleanUp();

                if (cache.asMap().size() >= maxTrackedKeys) {
                    return false;
                }
            }
        }

        boolean[] allowed = {false};

        cache.asMap().compute(key, (k, existing) -> {
            long now = ticker.read();
            Window current = existing;

            if (current == null || now - current.endsAtNanos() >= 0) {
                current = new Window(0, now + windowNanos);
            }

            if (current.count() >= maxRequests) {
                return current;
            }

            allowed[0] = true;
            return new Window(
                    current.count() + 1,
                    current.endsAtNanos()
            );
        });

        return allowed[0];
    }
}