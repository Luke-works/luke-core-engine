package com.luke.engine.capability.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.luke.engine.capability.access.CapabilityLevel.Action;
import org.junit.jupiter.api.Test;

/**
 * #104: the action-aware capability level model. {@code read-write} is grandfathered to permit
 * every action (so named actions change no existing behavior); {@code contributor} is the new,
 * more-restricted level (read + ordinary write, but NOT publish/delete); {@code read} is read-only.
 */
class CapabilityLevelTest {

    @Test
    void permitsMatrix() {
        // read → reads only
        assertThat(CapabilityLevel.permits("read", Action.READ)).isTrue();
        assertThat(CapabilityLevel.permits("read", Action.WRITE)).isFalse();
        assertThat(CapabilityLevel.permits("read", Action.PUBLISH)).isFalse();
        assertThat(CapabilityLevel.permits("read", Action.DELETE)).isFalse();

        // contributor → read + ordinary write, but NOT the privileged actions
        assertThat(CapabilityLevel.permits("contributor", Action.READ)).isTrue();
        assertThat(CapabilityLevel.permits("contributor", Action.WRITE)).isTrue();
        assertThat(CapabilityLevel.permits("contributor", Action.PUBLISH)).isFalse();
        assertThat(CapabilityLevel.permits("contributor", Action.DELETE)).isFalse();

        // read-write → everything (grandfathered — no behavior change from before named actions)
        assertThat(CapabilityLevel.permits("read-write", Action.READ)).isTrue();
        assertThat(CapabilityLevel.permits("read-write", Action.WRITE)).isTrue();
        assertThat(CapabilityLevel.permits("read-write", Action.PUBLISH)).isTrue();
        assertThat(CapabilityLevel.permits("read-write", Action.DELETE)).isTrue();
    }

    @Test
    void permitsIsFailClosedForNullLevelOrAction() {
        assertThat(CapabilityLevel.permits(null, Action.READ)).isFalse();
        assertThat(CapabilityLevel.permits("read-write", null)).isFalse();
        assertThat(CapabilityLevel.permits("bogus", Action.READ)).isFalse();
    }

    @Test
    void contributorIsAValidGrantLevel() {
        assertThat(CapabilityLevel.isValid("read")).isTrue();
        assertThat(CapabilityLevel.isValid("contributor")).isTrue();
        assertThat(CapabilityLevel.isValid("read-write")).isTrue();
        assertThat(CapabilityLevel.isValid("manage")).isFalse();
        assertThat(CapabilityLevel.isValid(null)).isFalse();
    }

    @Test
    void legacyHelpersStayConsistent() {
        // canRead: any of the three; canWrite: contributor + read-write.
        assertThat(CapabilityLevel.canRead("read")).isTrue();
        assertThat(CapabilityLevel.canRead("contributor")).isTrue();
        assertThat(CapabilityLevel.canWrite("read")).isFalse();
        assertThat(CapabilityLevel.canWrite("contributor")).isTrue();
        assertThat(CapabilityLevel.canWrite("read-write")).isTrue();

        // satisfies(level, needWrite) delegates to READ/WRITE — unchanged for read/read-write callers.
        assertThat(CapabilityLevel.satisfies("read", false)).isTrue();
        assertThat(CapabilityLevel.satisfies("read", true)).isFalse();
        assertThat(CapabilityLevel.satisfies("read-write", true)).isTrue();
        assertThat(CapabilityLevel.satisfies("contributor", true)).isTrue();
    }
}
