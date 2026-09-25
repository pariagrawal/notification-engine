package com.paridhi.notificationengine.messaging.consumer;

import com.paridhi.notificationengine.domain.Channel;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes the PUSH topic. Failures propagate so the shared error handler applies the
 * configured retries and then dead-letters.
 */
@Component
public class PushNotificationConsumer {

    private final ChannelMessageHandler handler;

    public PushNotificationConsumer(ChannelMessageHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            topics = "#{@kafkaTopics.forChannel(T(com.paridhi.notificationengine.domain.Channel).PUSH)}",
            groupId = "${notification.kafka.groups.push:notification-push}",
            concurrency = "${notification.kafka.concurrency.push:1}")
    public void onMessage(@Payload String payload) {
        handler.handle(payload, Channel.PUSH);
    }
}
