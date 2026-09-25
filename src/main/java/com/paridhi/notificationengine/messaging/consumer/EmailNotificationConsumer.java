package com.paridhi.notificationengine.messaging.consumer;

import com.paridhi.notificationengine.domain.Channel;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes the EMAIL topic. Failures propagate so the shared error handler applies the
 * configured retries and then dead-letters.
 */
@Component
public class EmailNotificationConsumer {

    private final ChannelMessageHandler handler;

    public EmailNotificationConsumer(ChannelMessageHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            topics = "#{@kafkaTopics.forChannel(T(com.paridhi.notificationengine.domain.Channel).EMAIL)}",
            groupId = "${notification.kafka.groups.email:notification-email}",
            concurrency = "${notification.kafka.concurrency.email:1}")
    public void onMessage(@Payload String payload) {
        handler.handle(payload, Channel.EMAIL);
    }
}
