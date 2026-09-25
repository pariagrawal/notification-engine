package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.config.IgniteCaches;
import com.paridhi.notificationengine.config.IgniteConfig;
import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import java.time.Duration;
import org.apache.ignite.client.ClientCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-user, per-channel fixed-window rate limiting, backed by an Ignite cache.
 *
 * <p>Ignite's thin client has no server-side atomic increment, so the counter is advanced
 * with a compare-and-set loop over {@code putIfAbsent} and {@code replace(key, old, new)}.
 * Both are atomic on the primary node, so a lost race is detected and retried rather than
 * silently overwriting a concurrent increment. A plain read-modify-write would undercount
 * under load and let a user past their quota.
 *
 * <p>The window's expiry is set when the counter is <em>created</em> and is never extended
 * by later increments, so a busy user's window still closes on schedule.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    /**
     * Bound on the compare-and-set retries. Contention on a single user's counter is low
     * by construction; exhausting this many attempts means something is badly wrong, and
     * spinning further would cost more than the quota is worth.
     */
    private static final int MAX_CAS_ATTEMPTS = 5;

    private final IgniteCaches caches;
    private final NotificationProperties properties;

    public RateLimiterService(IgniteCaches caches, NotificationProperties properties) {
        this.caches = caches;
        this.properties = properties;
    }

    /**
     * Records one request against the user's quota.
     *
     * @return the decision, including how long to wait if the quota is spent
     */
    public Decision tryAcquire(String userId, Channel channel) {
        NotificationProperties.RateLimit config = properties.getRateLimit();
        int limit = config.limitFor(channel);
        if (!config.isEnabled() || limit <= 0) {
            return Decision.allowed(limit, limit);
        }

        long windowSeconds = Math.max(1, config.getWindow().toSeconds());
        long windowStart = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = "%s:%s:%d".formatted(userId, channel.name(), windowStart);

        ClientCache<String, Long> cache = caches.rateLimit().orElse(null);
        if (cache == null) {
            // Fail open: an outage of the limiter should not stop notifications going out.
            return Decision.allowed(limit, limit);
        }

        long used;
        try {
            used = increment(cache, key, config.getWindow());
        } catch (RuntimeException ex) {
            // Fail open: an outage of the limiter should not stop notifications going
            // out. The trade-off is deliberate — losing the quota ceiling beats losing
            // delivery.
            log.warn("rate limiter unavailable, allowing request for user {}: {}", userId, ex.toString());
            return Decision.allowed(limit, limit);
        }

        if (used > limit) {
            long elapsedInWindow = System.currentTimeMillis() / 1000 % windowSeconds;
            return new Decision(false, limit, 0, Duration.ofSeconds(windowSeconds - elapsedInWindow));
        }
        return new Decision(true, limit, (int) Math.max(0, limit - used), Duration.ZERO);
    }

    /**
     * Advances the counter by one and returns its new value.
     *
     * <p>The expiry policy is attached to the creating write only. {@code replace} leaves
     * the existing expiry untouched, which is what makes this a fixed window rather than
     * a sliding one that a steady stream of requests could keep alive indefinitely.
     */
    private long increment(ClientCache<String, Long> cache, String key, Duration window) {
        ClientCache<String, Long> expiring =
                cache.withExpirePolicy(IgniteConfig.createdExpiry(window));

        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Long current = cache.get(key);
            if (current == null) {
                if (expiring.putIfAbsent(key, 1L)) {
                    return 1L;
                }
            } else if (cache.replace(key, current, current + 1)) {
                return current + 1;
            }
            // Another request updated the counter in between; re-read and try again.
        }

        log.warn("rate limit counter for {} stayed contended across {} attempts, allowing",
                key, MAX_CAS_ATTEMPTS);
        return 0L;
    }

    /**
     * @param allowed    whether the request may proceed
     * @param limit      the quota that applied
     * @param remaining  requests left in the current window
     * @param retryAfter how long until the window resets; zero when allowed
     */
    public record Decision(boolean allowed, int limit, int remaining, Duration retryAfter) {

        static Decision allowed(int limit, int remaining) {
            return new Decision(true, limit, remaining, Duration.ZERO);
        }
    }
}
