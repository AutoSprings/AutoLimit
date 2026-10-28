package com.autolimit.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * In memory cache of rate limits using Caffeine
 */
public class RateLimitService {
    private final Cache<String, long[]> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();

    // check if a user has requests in the cache, if they go over the max return false to throw 429
    public boolean isAllowed(String key, int maxRequests, long windowMillis) {
        long now = System.currentTimeMillis();

        long[] data = cache.get(key, k -> new long[]{0, now + windowMillis});

        if (now > data[1]) {
            data[0] = 1;
            data[1] = now + windowMillis;
        } else {
            data[0]++;
        }

        cache.put(key, data);
        return data[0] <= maxRequests;
    }
}