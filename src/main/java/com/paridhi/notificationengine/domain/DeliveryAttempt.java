package com.paridhi.notificationengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One audited call into a provider, successful or not. */
@Entity
@Table(name = "delivery_attempt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryOutcome outcome;

    @Column(length = 64)
    private String provider;

    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
