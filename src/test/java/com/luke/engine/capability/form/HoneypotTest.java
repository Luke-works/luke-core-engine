package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The honeypot bot-trap (Route B M5) — a filled hidden field signals a bot. */
class HoneypotTest {

    @Test
    void trippedWhenHoneypotFilled() {
        assertThat(Honeypot.tripped(Map.of(Honeypot.FIELD, "http://spam.example"))).isTrue();
    }

    @Test
    void notTrippedForRealSubmissions() {
        assertThat(Honeypot.tripped(Map.of(Honeypot.FIELD, ""))).isFalse(); // present but blank (human)
        assertThat(Honeypot.tripped(Map.of(Honeypot.FIELD, "   "))).isFalse();
        assertThat(Honeypot.tripped(Map.of("fullName", "Ada"))).isFalse(); // absent
        Map<String, Object> withNull = new HashMap<>();
        withNull.put(Honeypot.FIELD, null);
        assertThat(Honeypot.tripped(withNull)).isFalse();
        assertThat(Honeypot.tripped(null)).isFalse();
    }
}
