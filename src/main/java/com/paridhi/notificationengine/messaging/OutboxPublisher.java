package com.paridhi.notificationengine.messaging;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.OutboxEvent;
import com.paridhi.notificationengine.domain.OutboxStatus;
import com.paridhi.notificationengine.repository.OutboxEventRepository;
import com.paridhi.notificationengine.service.DeliveryService;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the transactional outbox onto Kafka.
 *
 * <p>Every node runs this. Safety comes from the claim query's
 * {@code for update skip locked}: concurrent pollers take disjoint batches instead of
 * fighting over the same rows.
 *
 * <p>The guarantee is at-least-once. A crash between a successful send and the status
 * update republishes the event, which is fine — consumers de-duplicate on the
 * notification's terminal status.
 */
@Component
@ConditionalOnProperty(prefix = "notification.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeliveryService deliveryService;
    private final NotificationProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository outbox,
                           KafkaTemplate<String, String> kafkaTemplate,
                           DeliveryService deliveryService,
                           NotificationProperties properties,
                           Clock clock) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-delay:500ms}")
    public void publishPending() {
        try {
            drainOnce();
        } catch (RuntimeException ex) {
            // Never let the scheduler's thread die on a transient database blip.
            log.error("outbox poll failed, will retry on the next tick", ex);
        }
    }

    /**
     * Claims and publishes one batch.
     *
     * <p>Transactional so the row locks taken by the claim query are held until the
     * statuses are written — that is what keeps another node from picking up the same
     * events mid-publish.
     */
    @Transactional
    public int drainOnce() {
        List<OutboxEvent> batch = outbox.claimPendingBatch(properties.getOutbox().getBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (OutboxEvent event : batch) {
            if (publish(event)) {
                published++;
            }
        }
        log.debug("outbox published {}/{} claimed events", published, batch.size());
        return published;
    }

    private boolean publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(clock.instant());
            event.setLastError(null);
            outbox.save(event);
            deliveryService.markPublished(event.getAggregateId());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(event, ex);
            return false;
        } catch (Exception ex) {
            recordFailure(event, ex);
            return false;
        }
    }

    /**
     * Leaves the event PENDING so the next poll retries it, until the attempt budget runs
     * out and it is parked as FAILED for an operator to look at.
     */
    private void recordFailure(OutboxEvent event, Exception ex) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(ex.toString());
        if (attempts >= properties.getOutbox().getMaxAttempts()) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("outbox event {} for notification {} gave up after {} attempts",
                    event.getId(), event.getAggregateId(), attempts, ex);
        } else {
            log.warn("outbox event {} publish attempt {} failed: {}", event.getId(), attempts, ex.toString());
        }
        outbox.save(event);
    }
}
