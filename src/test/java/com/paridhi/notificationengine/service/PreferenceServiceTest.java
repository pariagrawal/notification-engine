package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.UserPreference;
import com.paridhi.notificationengine.repository.UserPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreferenceServiceTest {

    /** 2026-03-10T14:00:00Z — mid-afternoon in UTC, late evening in Asia/Kolkata. */
    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");

    @Mock
    private UserPreferenceRepository repository;

    private NotificationProperties properties;
    private PreferenceService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        service = new PreferenceService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UserPreference preference(boolean enabled, Integer quietStart, Integer quietEnd, String zone) {
        return UserPreference.builder()
                .userId("user-1")
                .channel(Channel.EMAIL)
                .enabled(enabled)
                .destination("ada@example.com")
                .locale("en")
                .timeZone(zone)
                .quietHoursStart(quietStart)
                .quietHoursEnd(quietEnd)
                .build();
    }

    @Test
    void deliversToAUserWithNoPreferenceRowWhenChannelsAreOnByDefault() {
        // Transactional mail has to keep flowing for users who never opened settings.
        assertThat(service.evaluate(null, Channel.EMAIL).suppressed()).isFalse();
    }

    @Test
    void suppressesAUserWithNoPreferenceRowWhenChannelsAreOffByDefault() {
        properties.getPreferences().setDefaultEnabled(false);

        PreferenceService.Suppression result = service.evaluate(null, Channel.EMAIL);

        assertThat(result.suppressed()).isTrue();
        assertThat(result.reason()).contains("EMAIL");
    }

    @Test
    void suppressesAChannelTheUserTurnedOff() {
        PreferenceService.Suppression result = service.evaluate(preference(false, null, null, "UTC"), Channel.EMAIL);

        assertThat(result.suppressed()).isTrue();
        assertThat(result.reason()).contains("opted out");
    }

    @Test
    void deliversOutsideQuietHours() {
        // 14:00 UTC sits outside 22:00-07:00.
        assertThat(service.evaluate(preference(true, 22, 7, "UTC"), Channel.EMAIL).suppressed()).isFalse();
    }

    @Test
    void suppressesInsideQuietHours() {
        PreferenceService.Suppression result =
                service.evaluate(preference(true, 13, 16, "UTC"), Channel.EMAIL);

        assertThat(result.suppressed()).isTrue();
        assertThat(result.reason()).contains("quiet hours");
    }

    @Test
    void appliesQuietHoursInTheUsersOwnTimeZone() {
        // 14:00 UTC is 19:30 in Kolkata, which falls inside an 18:00-23:00 window even
        // though the same window would not catch it in UTC.
        assertThat(service.evaluate(preference(true, 18, 23, "Asia/Kolkata"), Channel.EMAIL).suppressed()).isTrue();
        assertThat(service.evaluate(preference(true, 18, 23, "UTC"), Channel.EMAIL).suppressed()).isFalse();
    }

    @ParameterizedTest(name = "{0}:00-{1}:00 at 14:00 UTC -> quiet={2}")
    @CsvSource({
            "13, 16, true",    // straightforward window containing 14
            "14, 15, true",    // start is inclusive
            "15, 16, false",   // before the window
            "10, 14, false",   // end is exclusive
            "22, 7,  false",   // wraps midnight, 14 is outside
            "10, 2,  true",    // wraps midnight, 14 is inside
    })
    void handlesWindowsThatWrapPastMidnight(int start, int end, boolean expectedQuiet) {
        assertThat(service.inQuietHours(preference(true, start, end, "UTC"))).isEqualTo(expectedQuiet);
    }

    @Test
    void ignoresAnEmptyQuietWindow() {
        assertThat(service.inQuietHours(preference(true, 14, 14, "UTC"))).isFalse();
    }

    @Test
    void ignoresAHalfConfiguredQuietWindow() {
        assertThat(service.inQuietHours(preference(true, 13, null, "UTC"))).isFalse();
        assertThat(service.inQuietHours(preference(true, null, 16, "UTC"))).isFalse();
    }

    @Test
    void fallsBackToTheDefaultZoneWhenTheStoredOneIsUnknown() {
        // A bad time zone must not take down the send path.
        assertThat(service.inQuietHours(preference(true, 13, 16, "Mars/Olympus_Mons"))).isTrue();
    }

    @Test
    void upsertFillsInDefaultsForLocaleAndTimeZone() {
        when(repository.findByUserIdAndChannel("user-1", Channel.SMS)).thenReturn(Optional.empty());
        when(repository.save(any(UserPreference.class))).thenAnswer(call -> call.getArgument(0));

        UserPreference saved = service.upsert("user-1", Channel.SMS, true, "+15551234567", null, null, null, null);

        assertThat(saved.getLocale()).isEqualTo("en");
        assertThat(saved.getTimeZone()).isEqualTo("UTC");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void upsertUpdatesAnExistingRowRatherThanCreatingASecondOne() {
        UserPreference existing = preference(true, null, null, "UTC");
        existing.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        when(repository.findByUserIdAndChannel("user-1", Channel.EMAIL)).thenReturn(Optional.of(existing));
        when(repository.save(any(UserPreference.class))).thenAnswer(call -> call.getArgument(0));

        UserPreference saved = service.upsert("user-1", Channel.EMAIL, false, "new@example.com", "fr", "Europe/Paris", 22, 7);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getDestination()).isEqualTo("new@example.com");
        assertThat(saved.getLocale()).isEqualTo("fr");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }
}
