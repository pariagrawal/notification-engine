package com.paridhi.notificationengine.exception;

import java.time.Duration;

/** The caller exceeded a per-user, per-channel quota. Surfaces as HTTP 429. */
public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final Duration retryAfter;

    public RateLimitExceededException(String message, int limit, Duration retryAfter) {
        super(message);
        this.limit = limit;
        this.retryAfter = retryAfter;
    }

    public int getLimit() {
        return limit;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
