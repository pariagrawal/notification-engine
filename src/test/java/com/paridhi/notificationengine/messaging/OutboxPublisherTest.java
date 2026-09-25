package com.paridhi.notificationengine.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.OutboxEvent;
import com.paridhi.notificationengine.domain.OutboxStatus;
import com.paridhi.notificationengine.repository.OutboxEventRepository;
import com.paridhi.notificationengine.service.DeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");

    @Mock
    private OutboxEventRepository outbox;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private DeliveryService deliveryService;

    private NotificationProperties properties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getOutbox().setBatchSize(50);
        properties.getOutbox().setMaxAttempts(3);
        publisher = new OutboxPublisher(outbox, kafkaTemplate, deliveryService, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OutboxEvent event(int attemptsSoFar) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("notification.requested")
                .topic("notifications.inbound")
                .messageKey("user-1")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .attempts(attemptsSoFar)
                .createdAt(NOW)
                .build();
    }

    private void sendSucceeds() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    private void sendFails() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unreachable")));
    }

    @Test
    void publishesEachClaimedEventOnItsOwnTopicAndKey() {
        OutboxEvent pending = event(0);
        when(outbox.claimPendingBatch(50)).thenReturn(List.of(pending));
        sendSucceeds();

        assertThat(publisher.drainOnce()).isEqualTo(1);

        verify(kafkaTemplate).send("notifications.inbound", "user-1", "{}");
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(pending.getPublishedAt()).isEqualTo(NOW);
        verify(deliveryService).markPublished(pending.getAggregateId());
    }

    @Test
    void doesNothingWhenTheOutboxIsEmpty() {
        when(outbox.claimPendingBatch(anyInt())).thenReturn(List.of());

        assertThat(publisher.drainOnce()).isZero();

        verifyNoInteractions(kafkaTemplate);
        verify(outbox, never()).save(any());
    }

    @Test
    void leavesAFailedEventPendingSoTheNextPollRetriesIt() {
        OutboxEvent pending = event(0);
        when(outbox.claimPendingBatch(anyInt())).thenReturn(List.of(pending));
        sendFails();

        assertThat(publisher.drainOnce()).isZero();

        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getLastError()).contains("broker unreachable");
        verify(outbox).save(pending);
        verify(deliveryService, never()).markPublished(any());
    }

    @Test
    void parksAnEventThatExhaustsItsAttemptBudget() {
        OutboxEvent nearlyDead = event(2);
        when(outbox.claimPendingBatch(anyInt())).thenReturn(List.of(nearlyDead));
        sendFails();

        publisher.drainOnce();

        assertThat(nearlyDead.getAttempts()).isEqualTo(3);
        assertThat(nearlyDead.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    void oneBadEventDoesNotStopTheRestOfTheBatch() {
        OutboxEvent first = event(0);
        OutboxEvent second = event(0);
        when(outbox.claimPendingBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unreachable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(publisher.drainOnce()).isEqualTo(1);

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void claimsOnlyAsManyEventsAsTheConfiguredBatchSize() {
        properties.getOutbox().setBatchSize(7);
        when(outbox.claimPendingBatch(7)).thenReturn(List.of());

        publisher.drainOnce();

        verify(outbox).claimPendingBatch(7);
    }

    @Test
    void theScheduledTickSurvivesADatabaseOutage() {
        // If this escaped, the scheduler would stop polling the outbox altogether.
        when(outbox.claimPendingBatch(anyInt())).thenThrow(new IllegalStateException("connection pool exhausted"));

        publisher.publishPending();
    }

    @Test
    void clearsAStaleErrorOnceTheEventGoesOut() {
        OutboxEvent recovered = event(1);
        recovered.setLastError("broker unreachable");
        when(outbox.claimPendingBatch(anyInt())).thenReturn(List.of(recovered));
        sendSucceeds();

        publisher.drainOnce();

        assertThat(recovered.getLastError()).isNull();
        verify(deliveryService).markPublished(eq(recovered.getAggregateId()));
    }
}
