package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.exception.TemplateRenderException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Substitutes {@code {{variable}}} placeholders in a template.
 *
 * <p>Deliberately not a general expression language: templates are operator-supplied
 * content rendered with caller-supplied data, and a full engine would turn that into a
 * server-side template injection surface. Placeholders name a key and nothing more.
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    /**
     * @param template the template text, or null
     * @param values   variables to substitute
     * @return the rendered text, or null if {@code template} was null
     * @throws TemplateRenderException if the template names a variable that is missing
     */
    public String render(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        Map<String, String> safeValues = values == null ? Map.of() : values;
        Set<String> missing = new LinkedHashSet<>();

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = safeValues.get(key);
            if (value == null) {
                missing.add(key);
                value = "";
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);

        if (!missing.isEmpty()) {
            throw new TemplateRenderException("missing template variables: " + String.join(", ", missing));
        }
        return rendered.toString();
    }

    /** The variable names a template references, in order of first appearance. */
    public Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
