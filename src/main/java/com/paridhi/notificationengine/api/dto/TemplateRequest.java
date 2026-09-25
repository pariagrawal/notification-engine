package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param subjectTemplate only meaningful for EMAIL and PUSH
 * @param bodyTemplate    template text using {@code {{variable}}} placeholders
 */
public record TemplateRequest(

        @NotBlank @Size(max = 100)
        String code,

        @NotNull
        Channel channel,

        @Size(max = 16)
        String locale,

        @Size(max = 500)
        String subjectTemplate,

        @NotBlank
        String bodyTemplate) {
}
