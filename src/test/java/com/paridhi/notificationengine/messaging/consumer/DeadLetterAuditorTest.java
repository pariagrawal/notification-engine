package com.paridhi.notificationengine.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.service.DeliveryService;
import com.paridhi.notificationengine.service.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DeadLetterAuditorTest {

    @Mock
    private DeliveryService deliveryService;

    private JsonCodec json;
    private DeadLetterAuditor auditor;

    @BeforeEach
    void setUp() {
        json = new JsonCodec(new ObjectMapper());
        auditor = new DeadLetterAuditor(deliveryService, json);
    }

    private ConsumerRecord<String, String> deadLetter(String payload, String exceptionType, String exceptionMessage) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("notifications.email.DLT", 0, 7L, "user-1", payload);
        if (exceptionType != null) {
            record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, exceptionType.getBytes(StandardCharsets.UTF_8));
        }
        if (exceptionMessage != null) {
            record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, exceptionMessage.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private String payload(UUID id) {
        return json.write(new NotificationMessage(id, "user-1", Channel.EMAIL, "welcome",
                "ada@example.com", "Hi", "Body", Map.of(), Instant.parse("2026-03-10T14:00:00Z")));
    }

    @Test
    void marksTheNotificationDeadLetteredWithTheOriginalFailure() {
        UUID id = UUID.randomUUID();

        auditor.onDeadLetter(deadLetter(payload(id),
                "com.paridhi.notificationengine.provider.TransientDeliveryException", "vendor 503"));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(deliveryService).markDeadLettered(eq(id), reason.capture());
        assertThat(reason.getValue()).contains("TransientDeliveryException").contains("vendor 503");
    }

    @Test
    void recordsTheUnderlyingCauseRatherThanSpringsListenerWrapper() {
        // What an operator reads in failureReason should name the real problem, not the
        // plumbing that reported it.
        UUID id = UUID.randomUUID();
        ConsumerRecord<String, String> record = deadLetter(payload(id),
                "org.springframework.kafka.listener.ListenerExecutionFailedException",
                "Listener method 'onMessage' threw exception; email-provider is temporarily unavailable");
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "com.paridhi.notificationengine.provider.TransientDeliveryException".getBytes(StandardCharsets.UTF_8));

        auditor.onDeadLetter(record);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(deliveryService).markDeadLettered(eq(id), reason.capture());
        assertThat(reason.getValue())
                .isEqualTo("TransientDeliveryException: email-provider is temporarily unavailable");
    }

    @Test
    void shortensTheExceptionTypeToSomethingReadable() {
        UUID id = UUID.randomUUID();

        auditor.onDeadLetter(deadLetter(payload(id),
                "com.paridhi.notificationengine.provider.PermanentDeliveryException", "hard bounce"));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(deliveryService).markDeadLettered(eq(id), reason.capture());
        assertThat(reason.getValue()).isEqualTo("PermanentDeliveryException: hard bounce");
    }

    @Test
    void stillRecordsTheOutcomeWhenTheRecordCarriesNoFailureHeaders() {
        UUID id = UUID.randomUUID();

        auditor.onDeadLetter(deadLetter(payload(id), null, null));

        verify(deliveryService).markDeadLettered(eq(id), anyString());
    }

    @Test
    void swallowsADeadLetterItCannotLinkToANotification() {
        // Re-throwing would dead-letter the dead letter and stall the auditor's offsets.
        auditor.onDeadLetter(deadLetter("not json", "java.lang.IllegalArgumentException", "unparseable"));

        verify(deliveryService, never()).markDeadLettered(any(), anyString());
    }
}
