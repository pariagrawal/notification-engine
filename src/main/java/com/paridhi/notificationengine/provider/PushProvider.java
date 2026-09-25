package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import org.springframework.stereotype.Component;

/** Simulated push transport. Replace with FCM / APNs for real delivery. */
@Component
public class PushProvider extends SimulatedProvider {

    private static final int MIN_TOKEN_LENGTH = 8;

    public PushProvider(NotificationProperties properties) {
        super(properties.getProviders());
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public String name() {
        return "push-provider";
    }

    @Override
    protected void validateRecipient(String recipient) {
        if (recipient.length() < MIN_TOKEN_LENGTH) {
            throw new PermanentDeliveryException("device token is too short to be valid: " + recipient);
        }
    }
}
