package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * @param duplicate true when the request matched an earlier idempotency key, so this is
 *                  the original notification rather than a new one
 */
public record NotificationResponse(
        UUID id,
        String userId,
        Channel channel,
        String templateCode,
        String recipient,
        String subject,
        String body,
        NotificationStatus status,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt,
        boolean duplicate) {

    public static NotificationResponse of(Notification notification, boolean duplicate) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getChannel(),
                notification.getTemplateCode(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getBody(),
                notification.getStatus(),
                notification.getAttempts(),
                notification.getFailureReason(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                notification.getSentAt(),
                duplicate);
    }

    public static NotificationResponse of(Notification notification) {
        return of(notification, false);
    }
}
