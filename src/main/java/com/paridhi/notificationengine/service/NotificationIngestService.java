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
 *   <li><b>Idempotency claim</b> first, so a client retrying a request that was already
 *       accepted gets the original back instead of spending quota or being throttled.
 *       Keys are scoped to the user: two users who both send {@code order-42} never see
 *       each other's notifications.</li>
 *   <li><b>Rate limit</b> second. A rejected request releases its claim, so the same key
 *       works once the window resets.</li>
 *   <li><b>Preferences and rendering</b>, which can suppress or reject the request.</li>
 *   <li><b>One transaction</b> writing the notification and its outbox event.</li>
 * </ol>
 * Every failure after the claim releases it, so an error never blocks a key for its TTL.
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
        String effectiveKey = scopedKey(request.userId(), idempotencyKey);

        UUID notificationId = UUID.randomUUID();
        Optional<UUID> existing = idempotency.claim(effectiveKey, notificationId);
        if (existing.isPresent()) {
            return replayOf(existing.get(), effectiveKey);
        }

        try {
            RateLimiterService.Decision decision = rateLimiter.tryAcquire(request.userId(), channel);
            if (!decision.allowed()) {
                throw new RateLimitExceededException(
                        "user %s exceeded %d %s notifications per window"
                                .formatted(request.userId(), decision.limit(), channel),
                        decision.limit(), decision.retryAfter());
            }
            return process(request, channel, notificationId, effectiveKey);
        } catch (DataIntegrityViolationException ex) {
            // Either the cache lost the claim (eviction, restart) but the database
            // remembered it, or some other constraint failed. The claim in the cache points
            // at a notification id that was never written, so it is released either way.
            idempotency.release(effectiveKey);
            Optional<Notification> original = notifications.findByIdempotencyKey(effectiveKey);
            if (original.isPresent()) {
                // The unique constraint on idempotency_key is why this is a duplicate
                // rather than a second delivery. Re-seed the cache with the original id so
                // later replays are answered on the fast path, before the rate limiter.
                idempotency.claim(effectiveKey, original.get().getId());
                log.info("idempotency key {} already committed, returning the original", effectiveKey);
                return new IngestResult(original.get(), true);
            }
            throw ex;
        } catch (RuntimeException ex) {
            idempotency.release(effectiveKey);
            throw ex;
        }
    }

    /**
     * Namespaces the caller's key by user, so a key only ever matches the same user's
     * earlier request. A request without a key gets a random one and is never de-duplicated.
     */
    static String scopedKey(String userId, String idempotencyKey) {
        String key = idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : UUID.randomUUID().toString();
        return userId + ":" + key;
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
