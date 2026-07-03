package com.luke.engine.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The seeded catalog resolves capabilities and publishes typed inputs for outbound actions. */
class StepTypeRegistryTest {

    private final StepTypeRegistry registry = new StepTypeRegistry();

    @Test
    void resolvesSeededStepTypesCaseInsensitively() {
        assertThat(registry.resolve("email", "action")).isPresent();
        assertThat(registry.resolve("EMAIL", "action")).isPresent();
        assertThat(registry.resolve("forms", "trigger")).isPresent();
    }

    @Test
    void emailSendPublishesTypedInputsWithRequiredTo() {
        StepTypeDescriptor email = registry.all().stream()
                .filter(d -> d.id().equals("email.send")).findFirst().orElseThrow();
        assertThat(email.inputs()).isNotEmpty();
        StepFieldDescriptor to = email.inputs().stream()
                .filter(f -> f.key().equals("to")).findFirst().orElseThrow();
        assertThat(to.required()).isTrue();
        assertThat(to.type()).isEqualTo("text");
    }

    @Test
    void stepsWithoutDeclaredInputsDefaultToEmptyList() {
        StepTypeDescriptor trigger = registry.all().stream()
                .filter(d -> d.id().equals("forms.submitted")).findFirst().orElseThrow();
        assertThat(trigger.inputs()).isEmpty();
    }
}
