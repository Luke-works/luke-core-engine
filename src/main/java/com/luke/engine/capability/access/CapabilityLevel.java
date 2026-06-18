package com.luke.engine.capability.access;

/**
 * The access level a user holds on a capability: {@code read} (look only) or
 * {@code read-write} (look + change). Kept as Strings to match the codebase's
 * status convention.
 */
public final class CapabilityLevel {

    public static final String READ = "read";
    public static final String READ_WRITE = "read-write";

    private CapabilityLevel() {}

    public static boolean isValid(String level) {
        return READ.equals(level) || READ_WRITE.equals(level);
    }

    public static boolean canRead(String level) {
        return READ.equals(level) || READ_WRITE.equals(level);
    }

    public static boolean canWrite(String level) {
        return READ_WRITE.equals(level);
    }

    /** Does {@code level} satisfy the requirement? {@code needWrite} → must be read-write. */
    public static boolean satisfies(String level, boolean needWrite) {
        return needWrite ? canWrite(level) : canRead(level);
    }
}
