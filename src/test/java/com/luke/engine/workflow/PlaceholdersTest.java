package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    @Test
    void resolvesDottedPathsAgainstNestedVariables() {
        Map<String, Object> vars = Map.of("submission", Map.of("email", "a@b.com"), "amount", 500);
        assertThat(Placeholders.resolve("Hi {{ submission.email }} — {{amount}}", vars))
                .isEqualTo("Hi a@b.com — 500");
    }

    @Test
    void unknownPathsAndNullVariablesResolveToEmpty() {
        assertThat(Placeholders.resolve("x={{ missing.path }}", Map.of())).isEqualTo("x=");
        assertThat(Placeholders.resolve("x={{ any }}", null)).isEqualTo("x=");
    }

    @Test
    void passesThroughStringsWithNoPlaceholders() {
        assertThat(Placeholders.resolve("plain", Map.of("a", 1))).isEqualTo("plain");
        assertThat(Placeholders.resolve(null, Map.of())).isNull();
    }
}
