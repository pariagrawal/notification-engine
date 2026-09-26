package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Simulated email transport: validates the address and logs the message instead of sending
 * it. Active by default; set {@code notification.providers.email.mode=smtp} (the
 * {@code gmail} profile does) to send real mail through {@link SmtpEmailProvider}.
 */
@Component
@ConditionalOnProperty(prefix = "notification.providers.email", name = "mode", havingValue = "simulated",
        matchIfMissing = true)
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

    /** Shared with {@link SmtpEmailProvider}, so both transports reject the same addresses. */
    static boolean isDeliverableAddress(String recipient) {
        return recipient != null && EMAIL.matcher(recipient).matches();
    }

    @Override
    protected void validateRecipient(String recipient) {
        if (!isDeliverableAddress(recipient)) {
            throw new PermanentDeliveryException("not a deliverable email address: " + recipient);
        }
    }
}
