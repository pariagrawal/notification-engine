package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.DeliveryAttempt;
import com.paridhi.notificationengine.domain.DeliveryOutcome;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.provider.DeliveryResult;
import com.paridhi.notificationengine.provider.NotificationProvider;
import com.paridhi.notificationengine.provider.PermanentDeliveryException;
import com.paridhi.notificationengine.provider.ProviderRegistry;
import com.paridhi.notificationengine.provider.TransientDeliveryException;
import com.paridhi.notificationengine.repository.DeliveryAttemptRepository;
import com.paridhi.notificationengine.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");
    private static final UUID ID = UUID.randomUUID();

    @Mock
    private NotificationRepository notifications;
    @Mock
    private DeliveryAttemptRepository attempts;
    @Mock
    private NotificationProvider provider;

    private DeliveryService service;

    @BeforeEach
    void setUp() {
        when(provider.channel()).thenReturn(Channel.EMAIL);
        when(provider.name()).thenReturn("email-provider");
        service = new DeliveryService(notifications, attempts, new ProviderRegistry(List.of(provider)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(notifications.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Notification stored(NotificationStatus status, int attemptsSoFar) {
        Notification notification = Notification.builder()
                .id(ID).userId("user-1").channel(Channel.EMAIL)
                .status(status).attempts(attemptsSoFar).build();
        when(notifications.findById(ID)).thenReturn(Optional.of(notification));
        return notification;
    }

    private NotificationMessage message() {
        return new NotificationMessage(ID, "user-1", Channel.EMAIL, "welcome",
                "ada@example.com", "Hi", "Body", Map.of(), NOW);
    }

    @Test
    void marksTheNotificationSentAndRecordsTheAttempt() {
        Notification notification = stored(NotificationStatus.PUBLISHED, 0);
        when(provider.send(any())).thenReturn(new DeliveryResult("msg-1", 12L));

        service.deliver(message());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW);
        assertThat(notification.getAttempts()).isEqualTo(1);

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().getOutcome()).isEqualTo(DeliveryOutcome.SUCCESS);
        assertThat(attempt.getValue().getProviderMessageId()).isEqualTo("msg-1");
        assertThat(attempt.getValue().getAttemptNo()).isEqualTo(1);
    }

    @Test
    void clearsAnEarlierFailureReasonOnceDeliverySucceeds() {
        Notification notification = stored(NotificationStatus.FAILED, 2);
        notification.setFailureReason("timeout");
        when(provider.send(any())).thenReturn(new DeliveryResult("msg-1", 3L));

        service.deliver(message());

        assertThat(notification.getFailureReason()).isNull();
        assertThat(notification.getAttempts()).isEqualTo(3);
    }

    @Test
    void rethrowsTransientFailuresSoKafkaRetriesThem() {
        stored(NotificationStatus.PUBLISHED, 0);
        when(provider.send(any())).thenThrow(new TransientDeliveryException("vendor 503"));

        assertThatThrownBy(() -> service.deliver(message()))
                .isInstanceOf(TransientDeliveryException.class)
                .hasMessageContaining("503");

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().getOutcome()).isEqualTo(DeliveryOutcome.TRANSIENT_FAILURE);
    }

    @Test
    void rethrowsPermanentFailuresSoTheyDeadLetterWithoutRetrying() {
        stored(NotificationStatus.PUBLISHED, 0);
        when(provider.send(any())).thenThrow(new PermanentDeliveryException("hard bounce"));

        assertThatThrownBy(() -> service.deliver(message()))
                .isInstanceOf(PermanentDeliveryException.class);

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().getOutcome()).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    }

    @Test
    void treatsAnUnexpectedProviderErrorAsRetryable() {
        stored(NotificationStatus.PUBLISHED, 0);
        when(provider.send(any())).thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> service.deliver(message()))
                .isInstanceOf(TransientDeliveryException.class)
                .hasMessageContaining("connection reset");
    }

    @Test
    void skipsAMessageWhoseNotificationWasAlreadySent() {
        // Kafka is at-least-once, so a redelivery must not produce a second email.
        stored(NotificationStatus.SENT, 1);

        service.deliver(message());

        verify(provider, never()).send(any());
        verifyNoInteractions(attempts);
    }

    @Test
    void skipsAMessageWhoseNotificationWasSuppressedOrDeadLettered() {
        stored(NotificationStatus.SUPPRESSED, 0);
        service.deliver(message());

        stored(NotificationStatus.DEAD_LETTERED, 4);
        service.deliver(message());

        verify(provider, never()).send(any());
    }

    @Test
    void deadLettersAMessageWhoseNotificationRowIsGone() {
        when(notifications.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deliver(message()))
                .isInstanceOf(PermanentDeliveryException.class)
                .hasMessageContaining(ID.toString());
    }

    @Test
    void countsEachDeliveryAttempt() {
        Notification notification = stored(NotificationStatus.FAILED, 2);
        when(provider.send(any())).thenThrow(new TransientDeliveryException("still down"));

        assertThatThrownBy(() -> service.deliver(message()))
                .isInstanceOf(TransientDeliveryException.class);

        assertThat(notification.getAttempts()).isEqualTo(3);
    }

    @Test
    void markDeadLetteredRecordsTheReason() {
        Notification notification = stored(NotificationStatus.FAILED, 4);

        service.markDeadLettered(ID, "TransientDeliveryException: vendor down");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTERED);
        assertThat(notification.getFailureReason()).contains("vendor down");
    }

    @Test
    void markPublishedAdvancesAQueuedNotification() {
        Notification notification = stored(NotificationStatus.QUEUED, 0);

        service.markPublished(ID);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
    }

    @Test
    void markPublishedNeverWalksBackATerminalStatus() {
        // A fast consumer can mark a notification SENT before the outbox poller gets
        // around to flagging it PUBLISHED; the later write must not undo the earlier one.
        Notification notification = stored(NotificationStatus.SENT, 1);

        service.markPublished(ID);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void truncatesAnAbsurdlyLongProviderError() {
        stored(NotificationStatus.PUBLISHED, 0);
        String hugeError = "x".repeat(5000);
        when(provider.send(any())).thenThrow(new TransientDeliveryException(hugeError));

        assertThatThrownBy(() -> service.deliver(message()))
                .isInstanceOf(TransientDeliveryException.class);

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().getError()).hasSize(2000);
    }
}
