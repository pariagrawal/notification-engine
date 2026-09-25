package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import java.util.Set;

/** @param variables placeholder names the template references, for callers building a form */
public record TemplateResponse(
        Long id,
        String code,
        Channel channel,
        String locale,
        String subjectTemplate,
        String bodyTemplate,
        Set<String> variables) {

    public static TemplateResponse of(NotificationTemplate template, Set<String> variables) {
        return new TemplateResponse(
                template.getId(),
                template.getCode(),
                template.getChannel(),
                template.getLocale(),
                template.getSubjectTemplate(),
                template.getBodyTemplate(),
                variables);
    }
}
