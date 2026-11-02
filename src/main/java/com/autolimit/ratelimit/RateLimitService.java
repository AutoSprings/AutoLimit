package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * In memory cache of rate limits using Caffeine
 */
public class RateLimitService {

    private record Window(long count, long endsAt) {}

    private final Cache<String, Window> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();

    // check if a user has requests in the cache, if they go over the max return false to throw 429
    public boolean isAllowed(String key, int maxRequests, long windowMillis) {

        boolean[] allowed = {false};

        cache.asMap().compute(key, (k, existing) -> {

            long now = System.currentTimeMillis();

            Window current = existing;

            if (current == null || now >= current.endsAt()) {
                current = new Window(0, now + windowMillis);
            }

            if (current.count() >= maxRequests) {
                return current;
            }

            allowed[0] = true;
            return new Window(current.count() + 1, current.endsAt());

        });

        return allowed[0];
    }
}