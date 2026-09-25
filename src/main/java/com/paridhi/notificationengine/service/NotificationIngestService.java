package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.api.dto.SendNotificationRequest;
import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import com.paridhi.notificationengine.domain.UserPreference;
import com.paridhi.notificationengine.exception.DuplicateInFlightException;
import com.paridhi.notificationengine.exception.InvalidRequestException;
import com.paridhi.notificationengine.exception.RateLimitExceededException;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import com.paridhi.notificationengine.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The write path behind {@code POST /api/v1/notifications}.
 *
 * <p>Order of operations is deliberate:
 * <ol>
 *   <li><b>Rate limit</b> first, so an abusive caller is rejected before any state is
 *       touched.</li>
 *   <li><b>Idempotency claim</b> second, so the claim is only spent on a request that
 *       will actually be processed. It is released if anything downstream fails.</li>
 *   <li><b>Preferences and rendering</b>, which can suppress or reject the request.</li>
 *   <li><b>One transaction</b> writing the notification and its outbox event.</li>
 * </ol>
 */
@Service
public class NotificationIngestService {

    private static final Logger log = LoggerFactory.getLogger(NotificationIngestService.class);

    private final NotificationRepository notifications;
    private final NotificationWriter writer;
    private final PreferenceService preferences;
    private final TemplateService templates;
    private final IdempotencyService idempotency;
    private final RateLimiterService rateLimiter;
    private final JsonCodec json;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationIngestService(NotificationRepository notifications,
                                     NotificationWriter writer,
                                     PreferenceService preferences,
                                     TemplateService templates,
                                     IdempotencyService idempotency,
                                     RateLimiterService rateLimiter,
                                     JsonCodec json,
                                     NotificationProperties properties,
                                     Clock clock) {
        this.notifications = notifications;
        this.writer = writer;
        this.preferences = preferences;
        this.templates = templates;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    public IngestResult ingest(SendNotificationRequest request, String idempotencyKey) {
        Channel channel = request.channel();
        String effectiveKey = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : UUID.randomUUID().toString();

        RateLimiterService.Decision decision = rateLimiter.tryAcquire(request.userId(), channel);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "user %s exceeded %d %s notifications per window"
                            .formatted(request.userId(), decision.limit(), channel),
                    decision.limit(), decision.retryAfter());
        }

        UUID notificationId = UUID.randomUUID();
        Optional<UUID> existing = idempotency.claim(effectiveKey, notificationId);
        if (existing.isPresent()) {
            return replayOf(existing.get(), effectiveKey);
        }

        try {
            return process(request, channel, notificationId, effectiveKey);
        } catch (DataIntegrityViolationException ex) {
            // The cache lost the claim (eviction, restart) but the database remembered it.
            // The unique constraint on idempotency_key is why this is a duplicate rather
            // than a second delivery.
            log.info("idempotency key {} already committed, returning the original", effectiveKey);
            return notifications.findByIdempotencyKey(effectiveKey)
                    .map(found -> new IngestResult(found, true))
                    .orElseThrow(() -> ex);
        } catch (RuntimeException ex) {
            idempotency.release(effectiveKey);
            throw ex;
        }
    }

    private IngestResult process(SendNotificationRequest request,
                                 Channel channel,
                                 UUID notificationId,
                                 String idempotencyKey) {
        UserPreference preference = preferences.find(request.userId(), channel).orElse(null);
        Instant now = clock.instant();
        Map<String, String> data = request.data() == null ? Map.of() : request.data();

        Notification notification = Notification.builder()
                .id(notificationId)
                .userId(request.userId())
                .channel(channel)
                .templateCode(request.templateCode())
                .idempotencyKey(idempotencyKey)
                .payload(json.write(data))
                .attempts(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        PreferenceService.Suppression suppression = preferences.evaluate(preference, channel);
        if (suppression.suppressed()) {
            notification.setStatus(NotificationStatus.SUPPRESSED);
            notification.setFailureReason(suppression.reason());
            notification.setRecipient(resolveRecipient(request, preference, channel, false));
            log.info("suppressed {} notification for user {}: {}",
                    channel, request.userId(), suppression.reason());
            return new IngestResult(writer.saveSuppressed(notification), false);
        }

        String recipient = resolveRecipient(request, preference, channel, true);
        String locale = request.locale() != null ? request.locale()
                : preference != null ? preference.getLocale()
                : properties.getPreferences().getDefaultLocale();

        NotificationTemplate template = templates.require(request.templateCode(), channel, locale);
        TemplateService.Rendered rendered = templates.render(template, data);

        notification.setRecipient(recipient);
        notification.setSubject(rendered.subject());
        notification.setBody(rendered.body());
        notification.setStatus(NotificationStatus.QUEUED);

        NotificationMessage message = new NotificationMessage(
                notificationId,
                request.userId(),
                channel,
                request.templateCode(),
                recipient,
                rendered.subject(),
                rendered.body(),
                request.metadata() == null ? Map.of() : request.metadata(),
                now);

        // Published to the single inbound topic; the router fans it out to the channel
        // topic. Keeping ingestion channel-agnostic means adding a channel touches the
        // router and a provider, not the write path.
        Notification saved = writer.saveQueued(notification, message, properties.getKafka().getInboundTopic());
        log.debug("queued notification {} for user {} on {}", saved.getId(), saved.getUserId(), channel);
        return new IngestResult(saved, false);
    }

    /**
     * An explicit {@code recipient} on the request wins, otherwise the user's stored
     * destination is used. Suppressed notifications tolerate having neither — there is
     * nowhere to send them anyway.
     */
    private String resolveRecipient(SendNotificationRequest request,
                                    UserPreference preference,
                                    Channel channel,
                                    boolean required) {
        if (request.recipient() != null && !request.recipient().isBlank()) {
            return request.recipient();
        }
        if (preference != null && preference.getDestination() != null && !preference.getDestination().isBlank()) {
            return preference.getDestination();
        }
        if (required) {
            throw new InvalidRequestException(
                    "no %s destination for user %s: set one in their preferences or pass 'recipient'"
                            .formatted(channel, request.userId()));
        }
        return null;
    }

    private IngestResult replayOf(UUID existingId, String idempotencyKey) {
        Notification original = notifications.findById(existingId)
                .orElseThrow(() -> new DuplicateInFlightException(
                        "a request with idempotency key '%s' is still in flight; retry shortly"
                                .formatted(idempotencyKey)));
        log.debug("replaying notification {} for idempotency key {}", existingId, idempotencyKey);
        return new IngestResult(original, true);
    }

    /**
     * @param notification the stored notification
     * @param duplicate    true when this request matched an earlier idempotency key and
     *                     nothing new was created
     */
    public record IngestResult(Notification notification, boolean duplicate) {
    }
}
