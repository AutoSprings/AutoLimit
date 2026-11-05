package com.autolimit.exception;

import java.util.Objects;
import java.util.OptionalLong;

/** Signals a rejected endpoint quota, optionally carrying retry timing. */
public class RateLimitExceededException extends RuntimeException {
    private final OptionalLong retryAfterSeconds;

    /**
     * Creates a rejection with no reliable retry delay.
     * @param message failure description
     */
    public RateLimitExceededException(String message) {
        this(message, OptionalLong.empty());
    }

    /**
     * Creates a rejection with optional retry timing from the admission decision.
     * @param message failure description
     * @param retryAfterSeconds positive whole-second delay, or empty if unknown
     */
    public RateLimitExceededException(String message, OptionalLong retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds");
        if (retryAfterSeconds.isPresent() && retryAfterSeconds.getAsLong() < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
    }

    /**
     * Returns the retry hint for the HTTP response.
     * @return the delay captured when quota was rejected, or empty if unknown
     */
    public OptionalLong getRetryAfterSeconds() { return retryAfterSeconds; }
}
