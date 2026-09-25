package com.paridhi.notificationengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single rendered notification. The row is the system of record; Kafka only ever
 * carries a reference to it plus the already-rendered content.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(name = "template_code", length = 100)
    private String templateCode;

    /**
     * Caller-supplied de-duplication key. Unique in the database, which is the durable
     * backstop behind the Ignite idempotency cache.
     */
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(length = 320)
    private String recipient;

    @Column(length = 500)
    private String subject;

    @Column(columnDefinition = "text")
    private String body;

    /** The raw template variables the caller sent, stored as JSON for audit. */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * Guards against two channel consumers (or a consumer and the DLT listener) writing
     * conflicting terminal states for the same notification.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
