package com.paridhi.notificationengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-user, per-channel delivery settings: whether the channel is on, where to deliver,
 * which locale to render in, and an optional nightly quiet window.
 */
@Entity
@Table(name = "user_preference")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(nullable = false)
    private boolean enabled;

    /** Email address, E.164 phone number, or device token, depending on the channel. */
    @Column(length = 320)
    private String destination;

    @Column(nullable = false, length = 16)
    private String locale;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    /** Inclusive local hour (0-23) at which quiet hours begin, or null for none. */
    @Column(name = "quiet_hours_start")
    private Integer quietHoursStart;

    /** Exclusive local hour (0-23) at which quiet hours end, or null for none. */
    @Column(name = "quiet_hours_end")
    private Integer quietHoursEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
