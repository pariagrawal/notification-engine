package com.paridhi.notificationengine.config;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.ClientCacheConfiguration;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lazily connects to Ignite and hands out the two caches the engine uses.
 *
 * <p>Connecting lazily rather than in a {@code @Bean} method is deliberate. Both features
 * backed by this cache — idempotency and rate limiting — are designed to degrade rather
 * than fail: the unique constraint on {@code notification.idempotency_key} still catches
 * duplicates, and the rate limiter deliberately fails open. A cache that the application
 * can survive losing at runtime should not be able to stop it booting, or a brief Ignite
 * outage during a deploy would take out every instance at once.
 *
 * <p>Callers get an empty {@link Optional} whenever the cluster is unreachable and are
 * expected to carry on without it.
 */
@Component
public class IgniteCaches {

    private static final Logger log = LoggerFactory.getLogger(IgniteCaches.class);

    private final NotificationProperties properties;

    private volatile IgniteClient client;
    private volatile ClientCache<String, String> idempotencyCache;
    private volatile ClientCache<String, Long> rateLimitCache;

    /**
     * Earliest time another connection attempt may run. Without this, every request would
     * pay a full connection timeout while the cluster is down.
     */
    private volatile long nextAttemptAtMillis;

    public IgniteCaches(NotificationProperties properties) {
        this.properties = properties;
    }

    public Optional<ClientCache<String, String>> idempotency() {
        return connected() ? Optional.of(idempotencyCache) : Optional.empty();
    }

    public Optional<ClientCache<String, Long>> rateLimit() {
        return connected() ? Optional.of(rateLimitCache) : Optional.empty();
    }

    /** True when the caches are usable, attempting a (throttled) connection if needed. */
    public boolean connected() {
        if (idempotencyCache != null) {
            return true;
        }
        return tryConnect();
    }

    private synchronized boolean tryConnect() {
        if (idempotencyCache != null) {
            return true;
        }
        if (System.currentTimeMillis() < nextAttemptAtMillis) {
            return false;
        }

        NotificationProperties.Ignite config = properties.getIgnite();
        try {
            log.info("connecting Ignite thin client to {}", config.getAddresses());
            IgniteClient newClient = Ignition.startClient(new ClientConfiguration()
                    .setAddresses(config.getAddresses().toArray(String[]::new))
                    .setTimeout((int) config.getTimeout().toMillis())
                    .setPartitionAwarenessEnabled(config.isPartitionAware())
                    .setRetryLimit(config.getRetryLimit()));

            ClientCache<String, String> idempotency = newClient.getOrCreateCache(cacheConfig(
                    config.getIdempotencyCache(), config.getBackups(),
                    properties.getIdempotency().getTtl()));
            ClientCache<String, Long> rateLimit = newClient.getOrCreateCache(cacheConfig(
                    config.getRateLimitCache(), config.getBackups(),
                    properties.getRateLimit().getWindow()));

            this.client = newClient;
            this.rateLimitCache = rateLimit;
            // Published last: idempotencyCache is what connected() checks, so it must not
            // become visible until everything behind it is ready.
            this.idempotencyCache = idempotency;
            log.info("Ignite caches ready: {}, {}", config.getIdempotencyCache(), config.getRateLimitCache());
            return true;
        } catch (RuntimeException ex) {
            nextAttemptAtMillis = System.currentTimeMillis() + config.getReconnectCooldown().toMillis();
            log.warn("Ignite unavailable ({}); idempotency falls back to the database constraint "
                    + "and rate limiting is disabled until it returns", ex.toString());
            return false;
        }
    }

    /** Names the cache for health reporting, without forcing a connection attempt. */
    public String idempotencyCacheName() {
        return properties.getIgnite().getIdempotencyCache();
    }

    /**
     * Entries expire a fixed time after they are <em>created</em>, never extending on
     * update. For the rate limiter that is the point: a fixed window has to end a fixed
     * time after it opened, however many requests land inside it.
     *
     * <p>This is only the cache's default — {@code getOrCreateCache} will not reconfigure
     * a cache that already exists, so the services also pass the policy per write, and
     * that is what governs a running cluster after a configuration change.
     */
    private ClientCacheConfiguration cacheConfig(String name, int backups, Duration ttl) {
        return new ClientCacheConfiguration()
                .setName(name)
                .setCacheMode(CacheMode.PARTITIONED)
                // Every operation here is a single atomic key update, so ATOMIC mode is
                // correct and much cheaper than a transactional cache's two-phase commit.
                .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                .setBackups(backups)
                .setExpiryPolicy(IgniteConfig.createdExpiry(ttl));
    }

    @PreDestroy
    void close() {
        IgniteClient current = client;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ex) {
                log.debug("error closing Ignite client", ex);
            }
        }
    }
}
