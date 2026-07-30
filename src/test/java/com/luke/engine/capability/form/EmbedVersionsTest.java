package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * Embed version resolution. The rule exists in ONE place because the public render and the public submit
 * must agree — a filler shown v3 must have their answers validated against v3.
 */
class EmbedVersionsTest {

    private static final IntPredicate ALL_EXIST = v -> true;
    private static final IntPredicate NONE_EXIST = v -> false;

    private FormDefinition form(String mode, Integer pinned, Integer published) {
        FormDefinition f = new FormDefinition();
        f.setEmbedVersionMode(mode);
        f.setEmbedVersion(pinned);
        f.setPublishedVersion(published);
        return f;
    }

    @Test
    void autoAlwaysServesThePublishedVersion() {
        // The default, and how embeds have always behaved: publishing reaches every live embed at once.
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_AUTO, null, 6), ALL_EXIST)).isEqualTo(6);
    }

    @Test
    void autoIgnoresAStalePinLeftBehindByAnEarlierPinnedPeriod() {
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_AUTO, 3, 6), ALL_EXIST)).isEqualTo(6);
    }

    @Test
    void pinnedHoldsFillersOnThePinnedVersion() {
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_PINNED, 3, 6), ALL_EXIST)).isEqualTo(3);
    }

    @Test
    void pinnedWithNoPinFallsBackToPublished() {
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_PINNED, null, 6), ALL_EXIST)).isEqualTo(6);
    }

    @Test
    void aPinPointingAtADeletedVersionFallsBackRatherThanBreakingTheEmbed() {
        // The embed lives on someone else's website — it must degrade to "serves the current form",
        // never to a blank iframe.
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_PINNED, 3, 6), NONE_EXIST)).isEqualTo(6);
    }

    @Test
    void unpublishedFormResolvesToNothing() {
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_AUTO, null, null), ALL_EXIST)).isNull();
        assertThat(EmbedVersions.resolve(form(FormDefinition.EMBED_MODE_PINNED, null, null), ALL_EXIST)).isNull();
    }

    @Test
    void updateAvailableOnlyWhenFillersAreBehind() {
        FormDefinition pinnedBehind = form(FormDefinition.EMBED_MODE_PINNED, 3, 6);
        assertThat(EmbedVersions.updateAvailable(pinnedBehind, 3)).isTrue();

        FormDefinition pinnedCurrent = form(FormDefinition.EMBED_MODE_PINNED, 6, 6);
        assertThat(EmbedVersions.updateAvailable(pinnedCurrent, 6)).isFalse();

        FormDefinition auto = form(FormDefinition.EMBED_MODE_AUTO, null, 6);
        assertThat(EmbedVersions.updateAvailable(auto, 6)).isFalse(); // auto is never behind

        assertThat(EmbedVersions.updateAvailable(form(FormDefinition.EMBED_MODE_AUTO, null, null), null)).isFalse();
    }
}
