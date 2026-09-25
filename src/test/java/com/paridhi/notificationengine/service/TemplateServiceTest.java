package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.repository.NotificationTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TemplateServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T14:00:00Z");

    @Mock
    private NotificationTemplateRepository repository;

    private NotificationProperties properties;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        service = new TemplateService(repository, new TemplateRenderer(), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private NotificationTemplate template(String locale) {
        return NotificationTemplate.builder()
                .id(1L).code("welcome").channel(Channel.EMAIL).locale(locale)
                .subjectTemplate("Welcome, {{firstName}}!")
                .bodyTemplate("Hi {{firstName}}, welcome to {{product}}.")
                .build();
    }

    @Test
    void resolvesTheExactLocaleWhenItExists() {
        when(repository.findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "fr"))
                .thenReturn(Optional.of(template("fr")));

        assertThat(service.resolve("welcome", Channel.EMAIL, "fr")).get()
                .extracting(NotificationTemplate::getLocale).isEqualTo("fr");
    }

    @Test
    void fallsBackToTheDefaultLocaleWhenATranslationIsMissing() {
        // Better to send an English welcome than nothing at all.
        when(repository.findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "fr")).thenReturn(Optional.empty());
        when(repository.findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "en"))
                .thenReturn(Optional.of(template("en")));

        assertThat(service.resolve("welcome", Channel.EMAIL, "fr")).get()
                .extracting(NotificationTemplate::getLocale).isEqualTo("en");
    }

    @Test
    void doesNotLookUpTheFallbackTwiceForTheDefaultLocale() {
        when(repository.findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "en")).thenReturn(Optional.empty());

        assertThat(service.resolve("welcome", Channel.EMAIL, "en")).isEmpty();

        verify(repository).findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "en");
    }

    @Test
    void treatsANullLocaleAsTheDefault() {
        when(repository.findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "en"))
                .thenReturn(Optional.of(template("en")));

        assertThat(service.resolve("welcome", Channel.EMAIL, null)).isPresent();
    }

    @Test
    void requireFailsLoudlyForAnUnknownTemplate() {
        when(repository.findByCodeAndChannelAndLocale(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require("nope", Channel.SMS, "en"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("SMS");
    }

    @Test
    void rendersBothSubjectAndBody() {
        TemplateService.Rendered rendered = service.render(template("en"),
                Map.of("firstName", "Ada", "product", "Acme"));

        assertThat(rendered.subject()).isEqualTo("Welcome, Ada!");
        assertThat(rendered.body()).isEqualTo("Hi Ada, welcome to Acme.");
    }

    @Test
    void rendersATemplateThatHasNoSubject() {
        NotificationTemplate sms = NotificationTemplate.builder()
                .code("welcome").channel(Channel.SMS).locale("en")
                .bodyTemplate("Welcome, {{firstName}}!").build();

        TemplateService.Rendered rendered = service.render(sms, Map.of("firstName", "Ada"));

        assertThat(rendered.subject()).isNull();
        assertThat(rendered.body()).isEqualTo("Welcome, Ada!");
    }

    @Test
    void upsertCreatesATemplateWithTheDefaultLocale() {
        when(repository.findByCodeAndChannelAndLocale("promo", Channel.PUSH, "en")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        NotificationTemplate saved = service.upsert("promo", Channel.PUSH, null, "Deal", "{{pct}} off");

        assertThat(saved.getLocale()).isEqualTo("en");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void upsertReplacesAnExistingTemplateRatherThanDuplicatingIt() {
        NotificationTemplate existing = template("en");
        existing.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        when(repository.findByCodeAndChannelAndLocale("welcome", Channel.EMAIL, "en"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        NotificationTemplate saved = service.upsert("welcome", Channel.EMAIL, "en", "New subject", "New body");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getBodyTemplate()).isEqualTo("New body");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void deleteFailsLoudlyForAnUnknownId() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(NotFoundException.class);
    }
}
