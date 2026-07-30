package com.luke.engine.capability.form;

/**
 * Resolves WHICH version the public embed surface serves for a form — the single answer both the
 * public render and the public submit must use.
 *
 * <p>Why one place: the render hands a filler a schema, and the submit validates their answers against
 * a schema. If those two ever resolved differently, a pinned embed would render v3 and validate against
 * v5 — silently dropping fields that only exist in v3 and rejecting the submission as incomplete. So the
 * rule lives here and both endpoints call it.
 *
 * <p>Fail-safe by design: a PINNED form whose pin is null, or points at a version that no longer exists,
 * falls back to {@code publishedVersion}. A stale pin must degrade to "serves the current form", never
 * to "the embed is broken" — the embed is on someone else's website and we cannot fix it from there.
 */
public final class EmbedVersions {

    private EmbedVersions() {}

    /**
     * The version this form's embeds should serve, or {@code null} when the form has no published
     * version at all (callers already 404 that case).
     *
     * @param versionExists tests whether a version number actually has a stored {@link FormVersion};
     *                      pass {@code v -> true} when the caller has already established it
     */
    public static Integer resolve(FormDefinition form, java.util.function.IntPredicate versionExists) {
        Integer published = form.getPublishedVersion();
        if (!FormDefinition.EMBED_MODE_PINNED.equals(form.getEmbedVersionMode())) return published;
        Integer pinned = form.getEmbedVersion();
        if (pinned == null) return published;                    // pinned but never set → current
        if (!versionExists.test(pinned)) return published;       // pin points at a deleted version
        return pinned;
    }

    /** True when this form's embeds are pinned to a version older than the latest published one, i.e. a
     *  publish has happened that fillers are NOT seeing yet. Drives the "Update embeds to vN" prompt. */
    public static boolean updateAvailable(FormDefinition form, Integer serving) {
        Integer published = form.getPublishedVersion();
        return published != null && serving != null && serving < published;
    }
}
