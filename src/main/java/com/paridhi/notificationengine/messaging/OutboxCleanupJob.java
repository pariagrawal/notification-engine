package com.paridhi.notificationengine.messaging;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes outbox rows that have been published longer than the retention window.
 *
 * <p>Without this the outbox table grows without bound and the poller's index scan over
 * pending rows slowly degrades.
 */
@Component
@ConditionalOnProperty(prefix = "notification.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final Clock clock;

    public OutboxCleanupJob(JdbcTemplate jdbc, NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${notification.outbox.cleanup-cron:0 0 * * * *}")
    @Transactional
    public void purgePublished() {
        Instant cutoff = clock.instant().minus(properties.getOutbox().getRetention());
        int deleted = jdbc.update(
                "delete from outbox_event where status = 'PUBLISHED' and published_at < ?",
                java.sql.Timestamp.from(cutoff));
        if (deleted > 0) {
            log.info("purged {} outbox events published before {}", deleted, cutoff);
        }
    }
}
