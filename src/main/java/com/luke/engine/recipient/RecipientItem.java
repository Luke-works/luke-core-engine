package com.luke.engine.recipient;

/**
 * One capability-agnostic thing waiting for a recipient in the portal — a form to fill, a document to
 * sign, a file to review, … Each {@link RecipientItemProvider} maps its own domain objects to this
 * shape; the portal frontend keys off {@link #type} to pick a renderer/action and reaches the item by
 * its opaque {@link #token} on that capability's own public surface.
 *
 * @param type      capability discriminator the UI renders on (e.g. {@code "form"}, {@code "signature"})
 * @param token     opaque handle used to open/act on the item (per-capability public API)
 * @param title     human title (e.g. the form name)
 * @param status    short status label (capability-defined; e.g. {@code "SENT"}, {@code "IN_PROGRESS"})
 * @param sentAt    epoch millis the item was sent, or null
 * @param expiresAt epoch millis the item expires, or null
 */
public record RecipientItem(
        String type,
        String token,
        String title,
        String status,
        Long sentAt,
        Long expiresAt) {}
