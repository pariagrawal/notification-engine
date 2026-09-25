package com.paridhi.notificationengine.config;

import org.apache.ignite.client.ClientCache;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the Ignite connection under {@code /actuator/health}.
 *
 * <p>Worth having precisely because both features backed by Ignite degrade silently: an
 * outage would otherwise be invisible from outside, with notifications still flowing
 * while quotas quietly stop being enforced.
 *
 * <p>Reported as {@code OUT_OF_SERVICE} rather than {@code DOWN}: the engine is still
 * accepting and delivering notifications, so a load balancer should not pull the instance
 * out of rotation over it.
 */
@Component("ignite")
public class IgniteHealthIndicator implements HealthIndicator {

    private final IgniteCaches caches;

    public IgniteHealthIndicator(IgniteCaches caches) {
        this.caches = caches;
    }

    @Override
    public Health health() {
        if (!caches.connected()) {
            return Health.outOfService()
                    .withDetail("cache", caches.idempotencyCacheName())
                    .withDetail("impact", "de-duplication falls back to the database; rate limiting is off")
                    .build();
        }
        try {
            // A cheap round trip, so this proves the cluster is reachable rather than
            // just that a client object exists.
            ClientCache<String, String> cache = caches.idempotency().orElseThrow();
            cache.size();
            return Health.up().withDetail("cache", cache.getName()).build();
        } catch (RuntimeException ex) {
            return Health.outOfService().withException(ex).build();
        }
    }
}
