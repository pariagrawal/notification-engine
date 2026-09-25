package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Simulated email transport. Replace with SES / SendGrid / SMTP for real delivery. */
@Component
public class EmailProvider extends SimulatedProvider {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    public EmailProvider(NotificationProperties properties) {
        super(properties.getProviders());
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public String name() {
        return "email-provider";
    }

    @Override
    protected void validateRecipient(String recipient) {
        if (!EMAIL.matcher(recipient).matches()) {
            throw new PermanentDeliveryException("not a deliverable email address: " + recipient);
        }
    }
}
