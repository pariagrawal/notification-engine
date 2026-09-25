package com.paridhi.notificationengine.api;

import com.paridhi.notificationengine.api.dto.TemplateRequest;
import com.paridhi.notificationengine.api.dto.TemplateResponse;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import com.paridhi.notificationengine.service.TemplateRenderer;
import com.paridhi.notificationengine.service.TemplateService;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateService templates;
    private final TemplateRenderer renderer;

    public TemplateController(TemplateService templates, TemplateRenderer renderer) {
        this.templates = templates;
        this.renderer = renderer;
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return templates.findAll().stream().map(this::toResponse).toList();
    }

    /** Creates or replaces the template for a (code, channel, locale) triple. */
    @PutMapping
    public TemplateResponse upsert(@Valid @RequestBody TemplateRequest request) {
        return toResponse(templates.upsert(
                request.code(),
                request.channel(),
                request.locale(),
                request.subjectTemplate(),
                request.bodyTemplate()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        templates.delete(id);
    }

    private TemplateResponse toResponse(NotificationTemplate template) {
        Set<String> variables = new LinkedHashSet<>(renderer.placeholders(template.getSubjectTemplate()));
        variables.addAll(renderer.placeholders(template.getBodyTemplate()));
        return TemplateResponse.of(template, variables);
    }
}
