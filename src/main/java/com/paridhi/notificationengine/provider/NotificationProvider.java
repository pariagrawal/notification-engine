package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;

/**
 * Integration point for a real delivery vendor (SES, Twilio, FCM, ...). Implement one per
 * channel and register it as a bean; {@code ProviderRegistry} picks it up by
 * {@link #channel()}.
 */
public interface NotificationProvider {

    Channel channel();

    /** Human-readable provider name, recorded on each {@code delivery_attempt}. */
    String name();

    /**
     * @throws TransientDeliveryException if the send should be retried
     * @throws PermanentDeliveryException if the send can never succeed as written
     */
    DeliveryResult send(NotificationMessage message);
}
