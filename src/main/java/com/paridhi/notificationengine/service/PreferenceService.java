package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.UserPreference;
import com.paridhi.notificationengine.repository.UserPreferenceRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes per-user channel settings, and decides whether a channel is deliverable now. */
@Service
public class PreferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceService.class);

    private final UserPreferenceRepository repository;
    private final NotificationProperties properties;
    private final Clock clock;

    public PreferenceService(UserPreferenceRepository repository,
                             NotificationProperties properties,
                             Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserPreference> findAll(String userId) {
        return repository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<UserPreference> find(String userId, Channel channel) {
        return repository.findByUserIdAndChannel(userId, channel);
    }

    /** Creates or replaces the settings for one user and channel. */
    @Transactional
    public UserPreference upsert(String userId,
                                 Channel channel,
                                 boolean enabled,
                                 String destination,
                                 String locale,
                                 String timeZone,
                                 Integer quietHoursStart,
                                 Integer quietHoursEnd) {
        Instant now = clock.instant();
        UserPreference preference = repository.findByUserIdAndChannel(userId, channel)
                .orElseGet(() -> UserPreference.builder()
                        .userId(userId)
                        .channel(channel)
                        .createdAt(now)
                        .build());

        preference.setEnabled(enabled);
        preference.setDestination(destination);
        preference.setLocale(locale != null ? locale : properties.getPreferences().getDefaultLocale());
        preference.setTimeZone(timeZone != null ? timeZone : properties.getPreferences().getDefaultTimeZone());
        preference.setQuietHoursStart(quietHoursStart);
        preference.setQuietHoursEnd(quietHoursEnd);
        preference.setUpdatedAt(now);
        return repository.save(preference);
    }

    /**
     * Decides whether a notification may go out on this channel right now.
     *
     * <p>A user with no preference row falls back to
     * {@code notification.preferences.default-enabled}, which keeps transactional mail
     * flowing for users who never touched their settings.
     */
    public Suppression evaluate(UserPreference preference, Channel channel) {
        if (preference == null) {
            return properties.getPreferences().isDefaultEnabled()
                    ? Suppression.none()
                    : Suppression.of("no preference on file and channel %s is off by default".formatted(channel));
        }
        if (!preference.isEnabled()) {
            return Suppression.of("user opted out of %s".formatted(channel));
        }
        if (inQuietHours(preference)) {
            return Suppression.of("quiet hours %02d:00-%02d:00 %s"
                    .formatted(preference.getQuietHoursStart(), preference.getQuietHoursEnd(),
                            preference.getTimeZone()));
        }
        return Suppression.none();
    }

    /**
     * True when the user's local hour falls in their quiet window. Windows that wrap past
     * midnight (22 to 7) are handled by inverting the comparison.
     */
    boolean inQuietHours(UserPreference preference) {
        Integer start = preference.getQuietHoursStart();
        Integer end = preference.getQuietHoursEnd();
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        int hour = ZonedDateTime.ofInstant(clock.instant(), zoneOf(preference)).getHour();
        return start < end
                ? hour >= start && hour < end
                : hour >= start || hour < end;
    }

    private ZoneId zoneOf(UserPreference preference) {
        try {
            return ZoneId.of(preference.getTimeZone());
        } catch (DateTimeException ex) {
            log.warn("user {} has unknown time zone {}, falling back to {}",
                    preference.getUserId(), preference.getTimeZone(),
                    properties.getPreferences().getDefaultTimeZone());
            return ZoneId.of(properties.getPreferences().getDefaultTimeZone());
        }
    }

    /**
     * @param suppressed whether delivery should be skipped
     * @param reason     why, recorded on the notification; null when not suppressed
     */
    public record Suppression(boolean suppressed, String reason) {

        static Suppression none() {
            return new Suppression(false, null);
        }

        static Suppression of(String reason) {
            return new Suppression(true, reason);
        }
    }
}
