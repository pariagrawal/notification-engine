package com.paridhi.notificationengine.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailProviderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailProvider provider = new SmtpEmailProvider(mailSender, "engine@example.com", "app-password");

    private NotificationMessage messageTo(String recipient) {
        return new NotificationMessage(UUID.randomUUID(), "user-1", Channel.EMAIL, "welcome",
                recipient, "Welcome aboard, Ada!", "Hi Ada", Map.of(), Instant.parse("2026-03-10T14:00:00Z"));
    }

    @Test
    void sendsTheRenderedSubjectAndBodyToTheRecipient() {
        NotificationMessage message = messageTo("ada@example.com");

        DeliveryResult result = provider.send(message);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("engine@example.com");
        assertThat(sent.getValue().getTo()).containsExactly("ada@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Welcome aboard, Ada!");
        assertThat(sent.getValue().getText()).isEqualTo("Hi Ada");
        assertThat(result.providerMessageId()).isEqualTo("smtp-" + message.notificationId());
    }

    @Test
    void rejectsAMalformedAddressWithoutContactingTheServer() {
        assertThatThrownBy(() -> provider.send(messageTo("not-an-address")))
                .isInstanceOf(PermanentDeliveryException.class);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void treatsAnUnreachableServerAsRetryable() {
        doThrow(new MailSendException("connection timed out")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> provider.send(messageTo("ada@example.com")))
                .isInstanceOf(TransientDeliveryException.class)
                .hasMessageContaining("connection timed out");
    }

    @Test
    void treatsRejectedCredentialsAsPermanentSoTheAccountIsNotHammered() {
        doThrow(new MailAuthenticationException("535 Username and Password not accepted"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> provider.send(messageTo("ada@example.com")))
                .isInstanceOf(PermanentDeliveryException.class)
                .hasMessageContaining("MAIL_PASSWORD");
    }

    @Test
    void refusesToStartWithoutASenderAddress() {
        assertThatThrownBy(() -> new SmtpEmailProvider(mailSender, "", "app-password"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAPassword() {
        // Otherwise the app would start and dead-letter every email with an auth error.
        assertThatThrownBy(() -> new SmtpEmailProvider(mailSender, "engine@example.com", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PASSWORD");
    }
}
