package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.api.dto.SendNotificationRequest;
import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import com.paridhi.notificationengine.domain.UserPreference;
import com.paridhi.notificationengine.exception.DuplicateInFlightException;
import com.paridhi.notificationengine.exception.InvalidRequestException;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.exception.RateLimitExceededException;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationIngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");

    @Mock
    private NotificationRepository notifications;
    @Mock
    private NotificationWriter writer;
    @Mock
    private PreferenceService preferences;
    @Mock
    private TemplateService templates;
    @Mock
    private IdempotencyService idempotency;
    @Mock
    private RateLimiterService rateLimiter;

    private NotificationProperties properties;
    private NotificationIngestService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        JsonCodec json = new JsonCodec(new tools.jackson.databind.ObjectMapper());
        service = new NotificationIngestService(notifications, writer, preferences, templates,
                idempotency, rateLimiter, json, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        allowRateLimit();
        allowIdempotencyClaim();
        when(preferences.evaluate(any(), any())).thenReturn(new PreferenceService.Suppression(false, null));
        when(templates.render(any(), any())).thenReturn(new TemplateService.Rendered("Hi Ada", "Body for Ada"));
        when(templates.require(anyString(), any(), anyString()))
                .thenReturn(NotificationTemplate.builder().code("welcome").channel(Channel.EMAIL).locale("en").build());
        when(writer.saveQueued(any(), any(), anyString())).thenAnswer(call -> call.getArgument(0));
        when(writer.saveSuppressed(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void allowRateLimit() {
        when(rateLimiter.tryAcquire(anyString(), any()))
                .thenReturn(new RateLimiterService.Decision(true, 20, 19, Duration.ZERO));
    }

    private void allowIdempotencyClaim() {
        when(idempotency.claim(anyString(), any())).thenReturn(Optional.empty());
    }

    private SendNotificationRequest request() {
        return new SendNotificationRequest("user-1", Channel.EMAIL, "welcome",
                Map.of("firstName", "Ada"), "ada@example.com", null, Map.of("campaign", "spring"));
    }

    @Test
    void queuesAValidRequestWithItsRenderedContent() {
        NotificationIngestService.IngestResult result = service.ingest(request(), "key-1");

        Notification saved = result.notification();
        assertThat(result.duplicate()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(saved.getSubject()).isEqualTo("Hi Ada");
        assertThat(saved.getBody()).isEqualTo("Body for Ada");
        assertThat(saved.getRecipient()).isEqualTo("ada@example.com");
        assertThat(saved.getIdempotencyKey()).isEqualTo("user-1:key-1");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void publishesToTheInboundTopicSoTheRouterCanFanOut() {
        service.ingest(request(), "key-1");

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        verify(writer).saveQueued(any(), any(), topic.capture());
        assertThat(topic.getValue()).isEqualTo("notifications.inbound");
    }

    @Test
    void carriesTheRenderedContentAndMetadataOntoTheWire() {
        service.ingest(request(), "key-1");

        ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(writer).saveQueued(any(), message.capture(), anyString());

        NotificationMessage published = message.getValue();
        assertThat(published.userId()).isEqualTo("user-1");
        assertThat(published.channel()).isEqualTo(Channel.EMAIL);
        assertThat(published.subject()).isEqualTo("Hi Ada");
        assertThat(published.body()).isEqualTo("Body for Ada");
        assertThat(published.metadata()).containsEntry("campaign", "spring");
    }

    @Test
    void generatesAnIdempotencyKeyWhenTheCallerOmitsOne() {
        NotificationIngestService.IngestResult result = service.ingest(request(), null);

        assertThat(result.notification().getIdempotencyKey()).isNotBlank();
    }

    @Test
    void rejectsACallerOverTheirQuotaAndReleasesTheClaim() {
        when(rateLimiter.tryAcquire(anyString(), any()))
                .thenReturn(new RateLimiterService.Decision(false, 5, 0, Duration.ofSeconds(30)));

        assertThatThrownBy(() -> service.ingest(request(), "key-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("user-1");

        // Released, so a later legitimate retry with the same key still works.
        verify(idempotency).release("user-1:key-1");
        verify(writer, never()).saveQueued(any(), any(), anyString());
    }

    @Test
    void aReplayIsAnsweredEvenWhenTheCallerIsOverQuota() {
        // A client retrying after a timeout must get its original notification back, not a
        // 429, and the retry must not spend quota.
        UUID originalId = UUID.randomUUID();
        Notification original = Notification.builder().id(originalId).status(NotificationStatus.SENT).build();
        when(idempotency.claim(eq("user-1:key-1"), any())).thenReturn(Optional.of(originalId));
        when(notifications.findById(originalId)).thenReturn(Optional.of(original));
        when(rateLimiter.tryAcquire(anyString(), any()))
                .thenReturn(new RateLimiterService.Decision(false, 5, 0, Duration.ofSeconds(30)));

        NotificationIngestService.IngestResult result = service.ingest(request(), "key-1");

        assertThat(result.duplicate()).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString(), any());
    }

    @Test
    void scopesIdempotencyKeysToTheUser() {
        // Two users choosing the same key must never see each other's notifications.
        SendNotificationRequest otherUser = new SendNotificationRequest("user-2", Channel.EMAIL, "welcome",
                Map.of("firstName", "Bo"), "bo@example.com", null, null);

        service.ingest(request(), "order-42");
        service.ingest(otherUser, "order-42");

        verify(idempotency).claim(eq("user-1:order-42"), any());
        verify(idempotency).claim(eq("user-2:order-42"), any());
    }

    @Test
    void releasesTheClaimWhenTheDatabaseRejectsTheInsert() {
        // Without the release, a failed insert would leave the key claimed in the cache,
        // pointing at a notification that was never written, and every retry would get
        // 409 until the TTL ran out.
        when(writer.saveQueued(any(), any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("value too long"));
        when(notifications.findByIdempotencyKey("user-1:key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(request(), "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(idempotency).release("user-1:key-1");
    }

    @Test
    void returnsTheOriginalNotificationWhenTheIdempotencyKeyRepeats() {
        UUID originalId = UUID.randomUUID();
        Notification original = Notification.builder().id(originalId).status(NotificationStatus.SENT).build();
        when(idempotency.claim(eq("user-1:key-1"), any())).thenReturn(Optional.of(originalId));
        when(notifications.findById(originalId)).thenReturn(Optional.of(original));

        NotificationIngestService.IngestResult result = service.ingest(request(), "key-1");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.notification()).isSameAs(original);
        verify(writer, never()).saveQueued(any(), any(), anyString());
    }

    @Test
    void reportsAConflictWhenTheMatchingRequestHasNotCommittedYet() {
        UUID inFlightId = UUID.randomUUID();
        when(idempotency.claim(eq("user-1:key-1"), any())).thenReturn(Optional.of(inFlightId));
        when(notifications.findById(inFlightId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(request(), "key-1"))
                .isInstanceOf(DuplicateInFlightException.class)
                .hasMessageContaining("key-1");
    }

    @Test
    void fallsBackToTheDatabaseWhenTheCacheForgotTheIdempotencyKey() {
        // The cache was flushed or unreachable, so the claim looked fresh; the unique
        // constraint is what caught the duplicate.
        Notification original = Notification.builder().id(UUID.randomUUID()).build();
        when(writer.saveQueued(any(), any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("uq_notification_idempotency_key"));
        when(notifications.findByIdempotencyKey("user-1:key-1")).thenReturn(Optional.of(original));

        NotificationIngestService.IngestResult result = service.ingest(request(), "key-1");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.notification()).isSameAs(original);
        // The cache is re-seeded with the original id, so the next replay skips the
        // rate limiter and the database round trip.
        verify(idempotency).release("user-1:key-1");
        verify(idempotency).claim("user-1:key-1", original.getId());
    }

    @Test
    void rethrowsWhenTheConstraintFiredButNoRowCanBeFound() {
        when(writer.saveQueued(any(), any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));
        when(notifications.findByIdempotencyKey("user-1:key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(request(), "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void releasesTheIdempotencyClaimWhenIngestionFails() {
        // Otherwise a transient failure would poison the key for its whole TTL and the
        // caller's retry would be answered with a notification that never existed.
        when(templates.require(anyString(), any(), anyString()))
                .thenThrow(new NotFoundException("no template"));

        assertThatThrownBy(() -> service.ingest(request(), "key-1")).isInstanceOf(NotFoundException.class);

        verify(idempotency).release("user-1:key-1");
    }

    @Test
    void storesASuppressedNotificationWithoutQueueingAnything() {
        when(preferences.evaluate(any(), any()))
                .thenReturn(new PreferenceService.Suppression(true, "user opted out of EMAIL"));

        NotificationIngestService.IngestResult result = service.ingest(request(), "key-1");

        assertThat(result.notification().getStatus()).isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(result.notification().getFailureReason()).isEqualTo("user opted out of EMAIL");
        verify(writer).saveSuppressed(any());
        verify(writer, never()).saveQueued(any(), any(), anyString());
    }

    @Test
    void doesNotRenderATemplateForASuppressedNotification() {
        when(preferences.evaluate(any(), any()))
                .thenReturn(new PreferenceService.Suppression(true, "quiet hours"));

        service.ingest(request(), "key-1");

        verify(templates, never()).require(anyString(), any(), anyString());
    }

    @Test
    void fallsBackToTheStoredDestinationWhenTheRequestOmitsOne() {
        when(preferences.find("user-1", Channel.EMAIL)).thenReturn(Optional.of(UserPreference.builder()
                .userId("user-1").channel(Channel.EMAIL).enabled(true)
                .destination("stored@example.com").locale("en").timeZone("UTC").build()));

        SendNotificationRequest noRecipient = new SendNotificationRequest(
                "user-1", Channel.EMAIL, "welcome", Map.of(), null, null, null);

        assertThat(service.ingest(noRecipient, "key-1").notification().getRecipient())
                .isEqualTo("stored@example.com");
    }

    @Test
    void prefersAnExplicitRecipientOverTheStoredDestination() {
        when(preferences.find("user-1", Channel.EMAIL)).thenReturn(Optional.of(UserPreference.builder()
                .userId("user-1").channel(Channel.EMAIL).enabled(true)
                .destination("stored@example.com").locale("en").timeZone("UTC").build()));

        assertThat(service.ingest(request(), "key-1").notification().getRecipient())
                .isEqualTo("ada@example.com");
    }

    @Test
    void refusesARequestWithNowhereToDeliver() {
        SendNotificationRequest noRecipient = new SendNotificationRequest(
                "user-1", Channel.EMAIL, "welcome", Map.of(), null, null, null);

        assertThatThrownBy(() -> service.ingest(noRecipient, "key-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("EMAIL");
    }

    @Test
    void rendersInTheUsersStoredLocaleUnlessTheRequestOverridesIt() {
        when(preferences.find("user-1", Channel.EMAIL)).thenReturn(Optional.of(UserPreference.builder()
                .userId("user-1").channel(Channel.EMAIL).enabled(true)
                .destination("ada@example.com").locale("fr").timeZone("UTC").build()));

        service.ingest(request(), "key-1");
        verify(templates).require("welcome", Channel.EMAIL, "fr");

        SendNotificationRequest germanRequest = new SendNotificationRequest(
                "user-1", Channel.EMAIL, "welcome", Map.of(), "ada@example.com", "de", null);
        service.ingest(germanRequest, "key-2");
        verify(templates).require("welcome", Channel.EMAIL, "de");
    }

    @Test
    void storesTheCallersVariablesForAudit() {
        assertThat(service.ingest(request(), "key-1").notification().getPayload())
                .isEqualTo("{\"firstName\":\"Ada\"}");
    }
}
