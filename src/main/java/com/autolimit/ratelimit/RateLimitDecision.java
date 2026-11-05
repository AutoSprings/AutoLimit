package com.autolimit.ratelimit;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * An admission result with an optional positive Retry-After delay in whole seconds.
 * @param allowed whether one request was admitted
 * @param retryAfterSeconds positive delay for a rejection, or empty if unknown
 */
public record RateLimitDecision(boolean allowed, OptionalLong retryAfterSeconds) {
    private static final RateLimitDecision PERMITTED = new RateLimitDecision(true, OptionalLong.empty());
    private static final RateLimitDecision DENIED = new RateLimitDecision(false, OptionalLong.empty());

    /**
     * Creates a validated admission decision.
     * @param allowed whether one request was admitted
     * @param retryAfterSeconds positive delay only for a rejection, or empty
     */
    public RateLimitDecision {
        Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds");
        if (retryAfterSeconds.isPresent() && (allowed || retryAfterSeconds.getAsLong() < 1)) {
            throw new IllegalArgumentException("Only a denied request may have a positive retry delay");
        }
    }

    /**
     * Describes a request admitted by the backend.
     * @return an admitted request, which has consumed one quota unit
     */
    public static RateLimitDecision permit() {
        return PERMITTED;
    }

    /**
     * Describes a rejection without known timing.
     * @return a rejection with no reliable retry time (for example, capacity exhaustion)
     */
    public static RateLimitDecision deny() {
        return DENIED;
    }

    /**
     * Describes a rejection with known timing.
     * @param seconds positive delay until retry, rounded up by the backend
     * @return a rejection with a known retry delay
     */
    public static RateLimitDecision deny(long seconds) {
        return new RateLimitDecision(false, OptionalLong.of(seconds));
    }
}
