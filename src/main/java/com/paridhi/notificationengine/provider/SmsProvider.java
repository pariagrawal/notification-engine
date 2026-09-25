package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Simulated SMS transport. Replace with Twilio / SNS for real delivery. */
@Component
public class SmsProvider extends SimulatedProvider {

    /** E.164: a leading +, then 8-15 digits. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    public SmsProvider(NotificationProperties properties) {
        super(properties.getProviders());
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public String name() {
        return "sms-provider";
    }

    @Override
    protected void validateRecipient(String recipient) {
        if (!E164.matcher(recipient).matches()) {
            throw new PermanentDeliveryException("not an E.164 phone number: " + recipient);
        }
    }
}
