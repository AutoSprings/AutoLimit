package com.autolimit.exception;

import java.util.Objects;
import java.util.OptionalLong;

/** Signals a rejected endpoint quota, optionally carrying retry timing. */
public class RateLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    // Zero means unknown; OptionalLong itself is not serializable.
    private final long retryAfterSeconds;

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
        Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds");
        if (retryAfterSeconds.isPresent() && retryAfterSeconds.getAsLong() < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds.orElse(0);
    }

    /**
     * Returns the retry hint for the HTTP response.
     * @return the delay captured when quota was rejected, or empty if unknown
     */
    public OptionalLong getRetryAfterSeconds() {
        return retryAfterSeconds == 0 ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }
}
