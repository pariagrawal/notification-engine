package com.paridhi.notificationengine.messaging.event;

import com.paridhi.notificationengine.domain.Channel;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The wire contract carried on every notification topic.
 *
 * <p>Content is rendered before it is published, so a consumer never needs the template
 * table and a template edit cannot retroactively change an in-flight message. The row in
 * {@code notification} remains the system of record; this is a self-contained copy of
 * what a provider needs.
 *
 * @param notificationId id of the owning {@code notification} row
 * @param userId         partition key, so one user's messages stay ordered per channel
 * @param channel        which provider should handle it
 * @param templateCode   template this was rendered from, for observability
 * @param recipient      address, phone number, or device token
 * @param subject        rendered subject; null for channels that have none
 * @param body           rendered body
 * @param metadata       free-form caller annotations, echoed to the provider
 * @param occurredAt     when the notification was accepted by the API
 */
public record NotificationMessage(
        UUID notificationId,
        String userId,
        Channel channel,
        String templateCode,
        String recipient,
        String subject,
        String body,
        Map<String, String> metadata,
        Instant occurredAt) {

    public static final String EVENT_TYPE = "notification.requested";
}
