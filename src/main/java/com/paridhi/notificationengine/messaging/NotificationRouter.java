package com.paridhi.notificationengine.messaging;

import com.paridhi.notificationengine.config.KafkaTopics;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.service.JsonCodec;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Fans the single inbound topic out to one topic per channel.
 *
 * <p>The split is what lets a slow or broken channel stay contained: SMS backing up
 * behind a throttling vendor leaves email and push untouched, and each channel's
 * consumer group scales on its own. Keying by user id preserves per-user ordering
 * within a channel.
 */
@Component
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopics topics;
    private final JsonCodec json;

    public NotificationRouter(KafkaTemplate<String, String> kafkaTemplate, KafkaTopics topics, JsonCodec json) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.json = json;
    }

    @KafkaListener(
            topics = "#{@kafkaTopics.inbound()}",
            groupId = "${notification.kafka.groups.router:notification-router}")
    public void route(@Payload String payload, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        NotificationMessage message = parse(payload);
        String destination = topics.forChannel(message.channel());
        try {
            kafkaTemplate.send(destination, key, payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("routed notification {} to {}", message.notificationId(), destination);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted routing notification " + message.notificationId(), ex);
        } catch (Exception ex) {
            // Rethrown so the error handler retries, and dead-letters if the broker stays
            // unreachable. Swallowing it here would silently drop the notification.
            throw new IllegalStateException(
                    "failed to route notification %s to %s".formatted(message.notificationId(), destination), ex);
        }
    }

    /**
     * A payload that will not parse can never parse, so it is reported as an
     * {@link IllegalArgumentException} — configured as non-retryable, which sends it
     * straight to the dead-letter topic instead of blocking the partition.
     */
    private NotificationMessage parse(String payload) {
        try {
            NotificationMessage message = json.read(payload, NotificationMessage.class);
            if (message.channel() == null || message.notificationId() == null) {
                throw new IllegalArgumentException("notification message is missing channel or id");
            }
            return message;
        } catch (JacksonException ex) {
            log.error("unparseable notification payload, dead-lettering: {}", payload, ex);
            throw new IllegalArgumentException("unparseable notification payload", ex);
        }
    }
}
