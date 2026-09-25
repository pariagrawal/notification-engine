package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.repository.NotificationTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Template CRUD plus the lookup-and-render step used during ingestion. */
@Service
public class TemplateService {

    private final NotificationTemplateRepository repository;
    private final TemplateRenderer renderer;
    private final NotificationProperties properties;
    private final Clock clock;

    public TemplateService(NotificationTemplateRepository repository,
                           TemplateRenderer renderer,
                           NotificationProperties properties,
                           Clock clock) {
        this.repository = repository;
        this.renderer = renderer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NotificationTemplate> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public NotificationTemplate require(String code, Channel channel, String locale) {
        return resolve(code, channel, locale)
                .orElseThrow(() -> new NotFoundException(
                        "no template '%s' for channel %s in locale %s".formatted(code, channel, locale)));
    }

    /**
     * Looks up a template, falling back to the configured default locale when the
     * requested one has no translation yet.
     */
    @Transactional(readOnly = true)
    public Optional<NotificationTemplate> resolve(String code, Channel channel, String locale) {
        String requested = locale != null ? locale : properties.getPreferences().getDefaultLocale();
        Optional<NotificationTemplate> exact = repository.findByCodeAndChannelAndLocale(code, channel, requested);
        if (exact.isPresent()) {
            return exact;
        }
        String fallback = properties.getPreferences().getDefaultLocale();
        return requested.equals(fallback)
                ? Optional.empty()
                : repository.findByCodeAndChannelAndLocale(code, channel, fallback);
    }

    @Transactional
    public NotificationTemplate upsert(String code,
                                       Channel channel,
                                       String locale,
                                       String subjectTemplate,
                                       String bodyTemplate) {
        Instant now = clock.instant();
        String effectiveLocale = locale != null ? locale : properties.getPreferences().getDefaultLocale();
        NotificationTemplate template = repository
                .findByCodeAndChannelAndLocale(code, channel, effectiveLocale)
                .orElseGet(() -> NotificationTemplate.builder()
                        .code(code)
                        .channel(channel)
                        .locale(effectiveLocale)
                        .createdAt(now)
                        .build());

        template.setSubjectTemplate(subjectTemplate);
        template.setBodyTemplate(bodyTemplate);
        template.setUpdatedAt(now);
        return repository.save(template);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("no template with id " + id);
        }
        repository.deleteById(id);
    }

    /** Renders a template's subject and body against the caller's variables. */
    public Rendered render(NotificationTemplate template, Map<String, String> data) {
        return new Rendered(
                renderer.render(template.getSubjectTemplate(), data),
                renderer.render(template.getBodyTemplate(), data));
    }

    public record Rendered(String subject, String body) {
    }
}
