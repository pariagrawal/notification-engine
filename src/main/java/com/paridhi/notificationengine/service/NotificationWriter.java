package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.OutboxEvent;
import com.paridhi.notificationengine.domain.OutboxStatus;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.repository.NotificationRepository;
import com.paridhi.notificationengine.repository.OutboxEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the one write that has to be atomic: the notification row and its outbox event.
 *
 * <p>Split out from {@code NotificationIngestService} so the transaction covers only the
 * database work — the cache calls around it are not part of it and must not extend it.
 */
@Service
public class NotificationWriter {

    private final NotificationRepository notifications;
    private final OutboxEventRepository outbox;
    private final JsonCodec json;
    private final Clock clock;

    public NotificationWriter(NotificationRepository notifications,
                              OutboxEventRepository outbox,
                              JsonCodec json,
                              Clock clock) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Persists an accepted notification together with the outbox row that owes it a Kafka
     * publish. Either both land or neither does, which is what stops the engine from
     * acknowledging a send it will never make (or making one it never accepted).
     */
    @Transactional
    public Notification saveQueued(Notification notification, NotificationMessage message, String topic) {
        Notification saved = notifications.save(notification);
        outbox.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(saved.getId())
                .eventType(NotificationMessage.EVENT_TYPE)
                .topic(topic)
                .messageKey(saved.getUserId())
                .payload(json.write(message))
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .createdAt(clock.instant())
                .build());
        return saved;
    }

    /** Persists a notification that preferences stopped, with no outbox event. */
    @Transactional
    public Notification saveSuppressed(Notification notification) {
        return notifications.save(notification);
    }
}
