package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.Objects;

/**
 * In memory cache of rate limits using Caffeine
 */
public class RateLimitService {

    private record Window(long count, long endsAtNanos) {}
    private final Ticker ticker;

    private final Cache<String, Window> cache;

    public RateLimitService() {
        this(Ticker.systemTicker());
    }

    RateLimitService(Ticker ticker) {
        this.ticker = Objects.requireNonNull(ticker);

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

        boolean[] allowed = {false};

        cache.asMap().compute(key, (k, existing) -> {

            long now = System.currentTimeMillis();

            Window current = existing;

            if (current == null || now >= current.endsAtNanos()) {
                current = new Window(0, now + windowMillis);
            }

            if (current.count() >= maxRequests) {
                return current;
            }

            allowed[0] = true;
            return new Window(current.count() + 1, current.endsAtNanos());

        });

        return allowed[0];
    }
}