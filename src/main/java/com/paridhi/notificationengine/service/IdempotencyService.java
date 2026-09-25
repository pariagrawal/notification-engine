package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.config.IgniteCaches;
import com.paridhi.notificationengine.config.IgniteConfig;
import com.paridhi.notificationengine.config.NotificationProperties;
import java.util.Optional;
import java.util.UUID;
import org.apache.ignite.client.ClientCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Request de-duplication backed by an Ignite cache.
 *
 * <p>The claim is a single {@code getAndPutIfAbsent}: it writes the key if absent and
 * returns whatever was already there, atomically. Doing it in one operation matters — a
 * write-then-read pair leaves a window in which the key can expire between the two calls,
 * and the second request would be treated as the first.
 *
 * <p>This is only the fast path, and it is designed to be lossy. The unique constraint on
 * {@code notification.idempotency_key} is the durable guarantee, so a cache outage makes
 * de-duplication cost a database round trip instead of taking ingestion down with it —
 * see {@link #claim}.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IgniteCaches caches;
    private final NotificationProperties properties;

    public IdempotencyService(IgniteCaches caches, NotificationProperties properties) {
        this.caches = caches;
        this.properties = properties;
    }

    /**
     * Attempts to claim {@code idempotencyKey} for {@code notificationId}.
     *
     * @return empty if the claim succeeded and the caller should proceed; otherwise the
     *         notification id recorded by the request that got there first
     */
    public Optional<UUID> claim(String idempotencyKey, UUID notificationId) {
        if (!properties.getIdempotency().isEnabled()) {
            return Optional.empty();
        }
        ClientCache<String, String> cache = caches.idempotency().orElse(null);
        if (cache == null) {
            log.debug("idempotency cache unavailable, relying on the database constraint");
            return Optional.empty();
        }

        String existing;
        try {
            // The TTL is applied per write rather than relying on the cache's configured
            // default, so changing notification.idempotency.ttl takes effect on a running
            // cluster instead of only on a cache that does not exist yet.
            // withExpirePolicy re-infers its type parameters, so it needs an explicit
            // target type rather than being chained straight into the call.
            ClientCache<String, String> expiring = cache
                    .withExpirePolicy(IgniteConfig.createdExpiry(properties.getIdempotency().getTtl()));
            existing = expiring.getAndPutIfAbsent(idempotencyKey, notificationId.toString());
        } catch (RuntimeException ex) {
            // Fail open, and lean on the database. The unique constraint on
            // notification.idempotency_key still rejects a genuine duplicate, and
            // ingestion turns that rejection back into the original notification. So a
            // cache outage costs a round trip to Postgres, not correctness — which is the
            // entire reason that constraint exists. Failing the request instead would
            // turn a cache outage into a full ingestion outage.
            log.warn("idempotency cache unavailable, falling back to the database constraint: {}",
                    ex.toString());
            return Optional.empty();
        }

        return existing == null ? Optional.empty() : parse(existing);
    }

    /**
     * Releases a claim so a failed request can be retried with the same key. Called when
     * ingestion fails after the claim was taken.
     */
    public void release(String idempotencyKey) {
        if (!properties.getIdempotency().isEnabled()) {
            return;
        }
        try {
            caches.idempotency().ifPresent(cache -> cache.remove(idempotencyKey));
        } catch (RuntimeException ex) {
            // Best-effort cleanup on a path that is already handling a failure. The claim
            // expires on its own, and this must not mask the error that got us here.
            log.warn("could not release idempotency key {}: {}", idempotencyKey, ex.toString());
        }
    }

    private Optional<UUID> parse(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            log.warn("ignoring malformed idempotency entry: {}", value);
            return Optional.empty();
        }
    }
}
