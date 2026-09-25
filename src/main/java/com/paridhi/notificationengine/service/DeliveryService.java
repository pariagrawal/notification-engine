package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.domain.DeliveryAttempt;
import com.paridhi.notificationengine.domain.DeliveryOutcome;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.provider.DeliveryResult;
import com.paridhi.notificationengine.provider.NotificationProvider;
import com.paridhi.notificationengine.provider.PermanentDeliveryException;
import com.paridhi.notificationengine.provider.ProviderRegistry;
import com.paridhi.notificationengine.provider.TransientDeliveryException;
import com.paridhi.notificationengine.repository.DeliveryAttemptRepository;
import com.paridhi.notificationengine.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs one delivery attempt and records what happened.
 *
 * <p>Shared by all three channel consumers: the only thing that differs per channel is
 * which provider handles the message, and {@link ProviderRegistry} resolves that.
 *
 * <p>{@link #deliver} runs its database steps through {@link TransactionOperations}
 * rather than calling the {@code @Transactional} methods below directly. A call from one
 * method of a bean to another never passes through Spring's proxy, so the annotations
 * would silently do nothing on that path. The annotations stay for callers in other beans
 * ({@code markPublished} from the outbox, {@code markDeadLettered} from the DLT auditor).
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int RECORD_SUCCESS_ATTEMPTS = 3;

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;
    private final ProviderRegistry providers;
    private final Clock clock;
    private final TransactionOperations transactions;

    public DeliveryService(NotificationRepository notifications,
                           DeliveryAttemptRepository attempts,
                           ProviderRegistry providers,
                           Clock clock,
                           TransactionOperations transactions) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.providers = providers;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * Delivers a message and records the attempt.
     *
     * <p>Failures are recorded and then rethrown: the consumer must not swallow them, or
     * Kafka would commit the offset and the configured retries would never happen.
     *
     * @throws TransientDeliveryException to ask Kafka for a retry with backoff
     * @throws PermanentDeliveryException to skip the retries and dead-letter immediately
     */
    public void deliver(NotificationMessage message) {
        Optional<Notification> claimed = transactions.execute(status -> beginAttempt(message.notificationId()));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        Notification notification = claimed.get();
        NotificationProvider provider = providers.require(message.channel());

        // Only the provider call is inside this try. Anything that fails here genuinely
        // did not deliver, so recording a failure and asking Kafka to retry is safe.
        DeliveryResult result;
        try {
            result = provider.send(message);
        } catch (PermanentDeliveryException ex) {
            transactions.executeWithoutResult(status -> recordFailure(notification.getId(),
                    notification.getAttempts(), DeliveryOutcome.PERMANENT_FAILURE, provider.name(), ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            transactions.executeWithoutResult(status -> recordFailure(notification.getId(),
                    notification.getAttempts(), DeliveryOutcome.TRANSIENT_FAILURE, provider.name(), ex.getMessage()));
            throw ex instanceof TransientDeliveryException retryable
                    ? retryable
                    : new TransientDeliveryException("delivery failed: " + ex.getMessage(), ex);
        }

        // The provider has accepted the message. From here on, a failure must NOT be
        // rethrown: Kafka would redeliver and the user would get the same message twice.
        // If recording SENT fails, the notification is left in its previous status and an
        // operator reconciles it from this log line and the provider's message id.
        // Only this bookkeeping step is retried, never the send: a concurrent status update
        // (the outbox flagging PUBLISHED) can trip the @Version check, and a fresh read
        // resolves it.
        DeliveryResult delivered = result;
        for (int attempt = 1; ; attempt++) {
            try {
                transactions.executeWithoutResult(status -> recordSuccess(notification.getId(),
                        notification.getAttempts(), provider.name(), delivered));
                break;
            } catch (OptimisticLockingFailureException ex) {
                if (attempt < RECORD_SUCCESS_ATTEMPTS) {
                    continue;
                }
                logUnrecordedDelivery(notification, provider, delivered, ex);
                return;
            } catch (RuntimeException ex) {
                logUnrecordedDelivery(notification, provider, delivered, ex);
                return;
            }
        }
        log.info("notification {} delivered via {} in {}ms",
                notification.getId(), provider.name(), delivered.latencyMs());
    }

    private void logUnrecordedDelivery(Notification notification,
                                       NotificationProvider provider,
                                       DeliveryResult delivered,
                                       RuntimeException ex) {
        log.error("notification {} WAS delivered via {} (provider message id {}) but recording SENT failed;"
                        + " not retrying, to avoid a duplicate send",
                notification.getId(), provider.name(), delivered.providerMessageId(), ex);
    }

    /**
     * Increments the attempt counter and hands back the notification, or empty if there
     * is nothing left to do.
     *
     * <p>This is the consumer-side idempotency guard. Kafka gives at-least-once delivery,
     * so a redelivered message must not produce a second send; a notification that is
     * already {@code SENT} (or was suppressed, or has been dead-lettered) is skipped.
     */
    @Transactional
    public Optional<Notification> beginAttempt(UUID notificationId) {
        Notification notification = notifications.findById(notificationId).orElse(null);
        if (notification == null) {
            // The outbox only publishes committed notifications, so this means the row
            // was purged. Nothing to deliver, and retrying will not bring it back.
            throw new PermanentDeliveryException("no notification row for id " + notificationId);
        }
        if (notification.getStatus().isTerminal()) {
            log.debug("skipping notification {}: already {}", notificationId, notification.getStatus());
            return Optional.empty();
        }
        notification.setAttempts(notification.getAttempts() + 1);
        notification.setUpdatedAt(clock.instant());
        return Optional.of(notifications.save(notification));
    }

    @Transactional
    public void recordSuccess(UUID notificationId, int attemptNo, String provider, DeliveryResult result) {
        Instant now = clock.instant();
        notifications.findById(notificationId).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(now);
            notification.setFailureReason(null);
            notification.setUpdatedAt(now);
            notifications.save(notification);
        });
        attempts.save(DeliveryAttempt.builder()
                .notificationId(notificationId)
                .attemptNo(attemptNo)
                .outcome(DeliveryOutcome.SUCCESS)
                .provider(provider)
                .providerMessageId(result.providerMessageId())
                .latencyMs(result.latencyMs())
                .createdAt(now)
                .build());
    }

    @Transactional
    public void recordFailure(UUID notificationId,
                              int attemptNo,
                              DeliveryOutcome outcome,
                              String provider,
                              String error) {
        Instant now = clock.instant();
        String trimmed = truncate(error);
        notifications.findById(notificationId).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(trimmed);
            notification.setUpdatedAt(now);
            notifications.save(notification);
        });
        attempts.save(DeliveryAttempt.builder()
                .notificationId(notificationId)
                .attemptNo(attemptNo)
                .outcome(outcome)
                .provider(provider)
                .error(trimmed)
                .createdAt(now)
                .build());
    }

    /**
     * Marks a notification as parked on a dead-letter topic; no further retries will run.
     *
     * <p>Never overwrites {@code SENT} or {@code SUPPRESSED}. Kafka is at-least-once, so a
     * duplicate copy of a message can exhaust its retries after another copy was already
     * delivered; the notification did reach the user, and its status must say so.
     */
    @Transactional
    public void markDeadLettered(UUID notificationId, String reason) {
        Instant now = clock.instant();
        notifications.findById(notificationId).ifPresent(notification -> {
            NotificationStatus current = notification.getStatus();
            if (current == NotificationStatus.SENT || current == NotificationStatus.SUPPRESSED) {
                log.warn("ignoring dead letter for notification {}: already {} ({})", notificationId, current, reason);
                return;
            }
            notification.setStatus(NotificationStatus.DEAD_LETTERED);
            notification.setFailureReason(truncate(reason));
            notification.setUpdatedAt(now);
            notifications.save(notification);
        });
    }

    /**
     * Records that the outbox handed the notification to Kafka.
     *
     * <p>Runs in its own short transaction, not the outbox poller's batch transaction. If
     * it joined the batch, the {@code PUBLISHED} write would sit uncommitted while the rest
     * of the batch was sent, racing the consumer's {@code SENT} write on the notification's
     * {@code @Version} and rolling back the whole batch when it lost.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID notificationId) {
        Instant now = clock.instant();
        notifications.findById(notificationId).ifPresent(notification -> {
            if (notification.getStatus() == NotificationStatus.QUEUED) {
                notification.setStatus(NotificationStatus.PUBLISHED);
                notification.setUpdatedAt(now);
                notifications.save(notification);
            }
        });
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
