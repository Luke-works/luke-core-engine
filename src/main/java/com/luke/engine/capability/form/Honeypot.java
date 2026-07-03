package com.luke.engine.capability.form;

import java.util.Map;

/**
 * A zero-dependency bot trap for the public embed surface (Route B M5). The embed renderer includes a
 * hidden field that real users never see or fill; automated spammers that blindly populate every input
 * will set it. A submission with this field non-blank is treated as a bot and silently dropped.
 *
 * <p>The field is carried under {@link #FIELD} in the raw submission and is checked BEFORE schema
 * validation (which would otherwise strip it as an unknown key).
 */
public final class Honeypot {

    private Honeypot() {}

    /** The data key the renderer submits the honeypot value under. */
    public static final String FIELD = "_lukehp";

    /** True if the honeypot was filled (a bot signal). Null/blank = a real submission. */
    public static boolean tripped(Map<String, Object> data) {
        if (data == null) return false;
        Object v = data.get(FIELD);
        return v != null && !String.valueOf(v).isBlank();
    }
}
