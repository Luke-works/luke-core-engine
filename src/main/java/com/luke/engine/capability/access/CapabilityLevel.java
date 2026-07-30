package com.luke.engine.capability.access;

/**
 * The access level a user holds on a capability. Historically two levels — {@code read}
 * (look only) and {@code read-write} (look + change) — with a route's requirement derived
 * purely from HTTP method. #104 adds finer-grained {@link Action named actions} so a route can
 * require more than "write" (e.g. {@code publish}, {@code delete}) without exploding the grant
 * model, plus a new {@code contributor} level that can create/edit ordinary content but NOT
 * perform those privileged actions.
 *
 * <p><b>Backward-compatible by construction.</b> {@code read-write} is grandfathered to permit
 * every action (including {@code publish}/{@code delete}), so introducing named actions changes
 * no existing behavior — the only genuinely new capability is the {@code contributor} level, which
 * no grant holds until an admin assigns it. Kept as Strings to match the codebase's status
 * convention.
 */
public final class CapabilityLevel {

    public static final String READ = "read";
    /** Create/edit ordinary content, but not privileged actions (publish, delete). #104. */
    public static final String CONTRIBUTOR = "contributor";
    public static final String READ_WRITE = "read-write";

    /** A named action a route can require, finer-grained than the read/write split. */
    public enum Action {
        /** Any safe/idempotent read (the default for GET/HEAD). */
        READ,
        /** Ordinary create/edit (the default for other methods). */
        WRITE,
        /** Promote/finalize: publish a version, sign-off, seal, retire/unretire. */
        PUBLISH,
        /** Irreversible hard-delete (purge). Distinct from a reversible soft-delete (WRITE). */
        DELETE
    }

    private CapabilityLevel() {}

    public static boolean isValid(String level) {
        return READ.equals(level) || CONTRIBUTOR.equals(level) || READ_WRITE.equals(level);
    }

    public static boolean canRead(String level) {
        return READ.equals(level) || CONTRIBUTOR.equals(level) || READ_WRITE.equals(level);
    }

    /** Ordinary write (create/edit). Both {@code contributor} and {@code read-write} can. */
    public static boolean canWrite(String level) {
        return CONTRIBUTOR.equals(level) || READ_WRITE.equals(level);
    }

    /** Does {@code level} permit {@code action}? {@code read-write} permits everything (grandfathered);
     *  {@code contributor} permits read + ordinary write only; {@code read} permits reads only. */
    public static boolean permits(String level, Action action) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case READ:
                return canRead(level);
            case WRITE:
                return canWrite(level);
            case PUBLISH:
            case DELETE:
                return READ_WRITE.equals(level);
            default:
                return false;
        }
    }

    /** Legacy read/write gate kept for callers that only distinguish the two (Documents, email assets).
     *  {@code needWrite} → ordinary {@link Action#WRITE}. */
    public static boolean satisfies(String level, boolean needWrite) {
        return permits(level, needWrite ? Action.WRITE : Action.READ);
    }

    /** Ascending privilege order: none(0) &lt; read(1) &lt; contributor(2) &lt; read-write(3). */
    public static int rank(String level) {
        if (READ_WRITE.equals(level)) return 3;
        if (CONTRIBUTOR.equals(level)) return 2;
        if (READ.equals(level)) return 1;
        return 0;
    }

    /**
     * Does {@code current} already include everything {@code requested} would grant?
     *
     * <p>Compares by RANK, which {@link #satisfies} cannot: it collapses levels into read/write
     * action classes, so it reports that a {@code read} holder already has what a
     * {@code contributor} request would give (both are non-write for its purposes), and that a
     * {@code contributor} already has {@code read-write} (both permit WRITE). Used by the
     * access-request duplicate check, where that collapse rejected every legitimate upgrade
     * through the middle level.
     */
    public static boolean atLeast(String current, String requested) {
        return rank(current) >= rank(requested);
    }
}
