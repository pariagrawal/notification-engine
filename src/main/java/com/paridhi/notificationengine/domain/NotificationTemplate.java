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
 * A channel- and locale-specific message template. Bodies use {@code {{placeholder}}}
 * syntax; see {@code TemplateRenderer}.
 */
@Entity
@Table(name = "notification_template")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(nullable = false, length = 16)
    private String locale;

    /** Only meaningful for EMAIL; SMS and PUSH ignore it. */
    @Column(name = "subject_template", length = 500)
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "text")
    private String bodyTemplate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
