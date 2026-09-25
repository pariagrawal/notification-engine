package com.paridhi.notificationengine.messaging.consumer;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.service.DeliveryService;
import com.paridhi.notificationengine.service.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * The work every channel consumer does: parse, sanity-check the channel, deliver.
 *
 * <p>The consumers themselves are thin so that each channel keeps its own consumer group
 * and concurrency while the logic stays in one place.
 */
@Component
public class ChannelMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageHandler.class);

    private final DeliveryService deliveryService;
    private final JsonCodec json;

    public ChannelMessageHandler(DeliveryService deliveryService, JsonCodec json) {
        this.deliveryService = deliveryService;
        this.json = json;
    }

    /**
     * @throws com.paridhi.notificationengine.provider.TransientDeliveryException to ask
     *         for a retry
     * @throws com.paridhi.notificationengine.provider.PermanentDeliveryException to
     *         dead-letter immediately
     * @throws IllegalArgumentException for a payload no retry could fix
     */
    public void handle(String payload, Channel expected) {
        NotificationMessage message;
        try {
            message = json.read(payload, NotificationMessage.class);
        } catch (JacksonException ex) {
            log.error("unparseable payload on the {} topic, dead-lettering: {}", expected, payload, ex);
            throw new IllegalArgumentException("unparseable notification payload", ex);
        }
        if (message.channel() != expected) {
            // Only reachable if something published straight to a channel topic, bypassing
            // the router. Delivering it anyway would send, say, an SMS body as an email.
            throw new IllegalArgumentException(
                    "message for channel %s arrived on the %s topic".formatted(message.channel(), expected));
        }
        deliveryService.deliver(message);
    }
}
