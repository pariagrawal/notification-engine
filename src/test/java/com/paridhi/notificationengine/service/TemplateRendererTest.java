package com.paridhi.notificationengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paridhi.notificationengine.exception.TemplateRenderException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void substitutesEveryPlaceholder() {
        String rendered = renderer.render(
                "Hi {{firstName}}, order {{orderId}} ships {{eta}}.",
                Map.of("firstName", "Ada", "orderId", "A-17", "eta", "Tuesday"));

        assertThat(rendered).isEqualTo("Hi Ada, order A-17 ships Tuesday.");
    }

    @ParameterizedTest
    @CsvSource({"'{{name}}'", "'{{ name }}'", "'{{  name  }}'"})
    void toleratesWhitespaceInsideBraces(String template) {
        assertThat(renderer.render(template, Map.of("name", "Ada"))).isEqualTo("Ada");
    }

    @Test
    void substitutesTheSamePlaceholderEverywhereItAppears() {
        assertThat(renderer.render("{{a}}-{{a}}-{{a}}", Map.of("a", "x"))).isEqualTo("x-x-x");
    }

    @Test
    void reportsEveryMissingVariableAtOnce() {
        assertThatThrownBy(() -> renderer.render("{{a}} {{b}} {{c}}", Map.of("b", "present")))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessage("missing template variables: a, c");
    }

    @Test
    void treatsNullValuesAsNoValuesAtAll() {
        assertThatThrownBy(() -> renderer.render("{{a}}", null))
                .isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void returnsNullForANullTemplate() {
        // SMS templates have no subject; rendering one must not blow up.
        assertThat(renderer.render(null, Map.of("a", "b"))).isNull();
    }

    @Test
    void leavesTextWithoutPlaceholdersUntouched() {
        assertThat(renderer.render("nothing to see", Map.of())).isEqualTo("nothing to see");
    }

    @Test
    void treatsSubstitutedValuesAsLiteralText() {
        // A value containing $1 or a backslash must not be read as a regex replacement,
        // and a value that looks like a placeholder must not be expanded again.
        String rendered = renderer.render("{{a}}|{{b}}", Map.of("a", "$1\\x", "b", "{{a}}"));

        assertThat(rendered).isEqualTo("$1\\x|{{a}}");
    }

    @Test
    void listsPlaceholdersInOrderWithoutDuplicates() {
        assertThat(renderer.placeholders("{{b}} {{a}} {{b}}")).containsExactly("b", "a");
    }

    @Test
    void listsNoPlaceholdersForANullTemplate() {
        assertThat(renderer.placeholders(null)).isEmpty();
    }
}
