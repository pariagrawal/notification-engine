package com.paridhi.notificationengine.messaging.consumer;

import com.paridhi.notificationengine.domain.Channel;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes the SMS topic. Failures propagate so the shared error handler applies the
 * configured retries and then dead-letters.
 */
@Component
public class SmsNotificationConsumer {

    private final ChannelMessageHandler handler;

    public SmsNotificationConsumer(ChannelMessageHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            topics = "#{@kafkaTopics.forChannel(T(com.paridhi.notificationengine.domain.Channel).SMS)}",
            groupId = "${notification.kafka.groups.sms:notification-sms}",
            concurrency = "${notification.kafka.concurrency.sms:1}")
    public void onMessage(@Payload String payload) {
        handler.handle(payload, Channel.SMS);
    }
}
