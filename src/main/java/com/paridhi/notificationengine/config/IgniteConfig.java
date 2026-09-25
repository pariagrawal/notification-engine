package com.paridhi.notificationengine.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

/**
 * Shared Ignite helpers.
 *
 * <p>The connection itself lives in {@link IgniteCaches}, which owns it lazily so that an
 * unreachable cluster degrades the engine rather than stopping it from starting.
 */
public final class IgniteConfig {

    private IgniteConfig() {
    }

    /** Builds an "expire {@code ttl} after creation, never on update or access" policy. */
    public static ExpiryPolicy createdExpiry(Duration ttl) {
        return new CreatedExpiryPolicy(
                new javax.cache.expiry.Duration(TimeUnit.MILLISECONDS, ttl.toMillis()));
    }
}
