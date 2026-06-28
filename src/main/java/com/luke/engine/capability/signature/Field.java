package com.luke.engine.capability.signature;

/**
 * Signature field placement, in UI coordinates (page index + a rectangle with the origin at
 * the page's TOP-left). The signing engine (SIG-2) flips Y to PDF's bottom-left origin.
 */
public record Field(int page, double x, double y, double w, double h) {

    /** Build from the flat columns persisted on a {@link SignatureRequest}. */
    public static Field of(SignatureRequest r) {
        return new Field(r.getFieldPage(), r.getFieldX(), r.getFieldY(), r.getFieldW(), r.getFieldH());
    }
}
