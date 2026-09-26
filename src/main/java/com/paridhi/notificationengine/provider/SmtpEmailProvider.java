package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real email delivery over SMTP (Gmail, or any SMTP server configured under
 * {@code spring.mail.*}). Replaces the simulated {@link EmailProvider} when
 * {@code notification.providers.email.mode=smtp}; everything upstream of the provider is
 * unchanged, which is the point of the {@link NotificationProvider} seam.
 *
 * <p>Failures are classified so the retry machinery does the right thing:
 * <ul>
 *   <li>A malformed address, or a message that cannot be built, is <b>permanent</b>:
 *       retrying cannot fix it.</li>
 *   <li>Rejected credentials are also <b>permanent</b>: it is a configuration error, and
 *       hammering the server with bad logins only gets the account locked.</li>
 *   <li>Anything else (connection refused, timeout, a 4xx from the server) is
 *       <b>transient</b> and goes through the backoff-and-retry path.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "notification.providers.email", name = "mode", havingValue = "smtp")
public class SmtpEmailProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailProvider.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailProvider(JavaMailSender mailSender,
                             @Value("${notification.providers.email.from:${spring.mail.username:}}") String from,
                             @Value("${spring.mail.password:}") String password) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP email needs a sender: set notification.providers.email.from or spring.mail.username");
        }
        // Fail at startup rather than dead-lettering every email with an auth error later.
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("SMTP email needs a password: set MAIL_PASSWORD (a Gmail app password)");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public DeliveryResult send(NotificationMessage message) {
        long startedAt = System.nanoTime();
        if (!EmailProvider.isDeliverableAddress(message.recipient())) {
            throw new PermanentDeliveryException("not a deliverable email address: " + message.recipient());
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.recipient());
        mail.setSubject(message.subject());
        mail.setText(message.body());

        try {
            mailSender.send(mail);
        } catch (MailAuthenticationException ex) {
            throw new PermanentDeliveryException(
                    "SMTP server rejected the credentials; check MAIL_USERNAME / MAIL_PASSWORD: " + ex.getMessage(), ex);
        } catch (MailParseException | MailPreparationException ex) {
            throw new PermanentDeliveryException("email could not be built: " + ex.getMessage(), ex);
        } catch (MailException ex) {
            // Includes permanent server rejections (e.g. 550 "no such user"), which are
            // retried until the attempt budget is spent and then dead-lettered. Telling
            // them apart would mean inspecting the SMTP reply codes per failed message.
            throw new TransientDeliveryException("SMTP send failed: " + ex.getMessage(), ex);
        }

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[smtp] sent notification {} to {} in {}ms", message.notificationId(), message.recipient(), latencyMs);
        // SimpleMailMessage does not expose the server-assigned Message-ID, so the
        // notification id is recorded instead; it is unique per notification.
        return new DeliveryResult("smtp-" + message.notificationId(), latencyMs);
    }
}
