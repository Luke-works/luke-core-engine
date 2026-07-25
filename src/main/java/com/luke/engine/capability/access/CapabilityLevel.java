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

    /** Legacy read/write gate kept for callers that only distinguish the two (Documents, email assets,
     *  access-request short-circuit). {@code needWrite} → ordinary {@link Action#WRITE}. */
    public static boolean satisfies(String level, boolean needWrite) {
        return permits(level, needWrite ? Action.WRITE : Action.READ);
    }
}
