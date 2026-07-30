package com.luke.engine.tenant;

import java.util.List;
import java.util.Locale;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;

/**
 * Per-capability RESOURCE OWNERS: who decides access requests for ONE capability in ONE tenant,
 * without being a full tenant owner. Lets an org delegate "who may approve Forms access" to the
 * people who actually own forms, while access to Email is decided by someone else.
 *
 * <p>Owners are members of a dedicated group {@code capowner:<tenantId>:<CODE>} whose
 * {@link #GROUP_TYPE type} is {@code CAP_OWNERSHIP} — deliberately not
 * {@code ROLE}/{@code ORGANIZATIONAL}/{@code OWNERSHIP}/{@code CG_OWNERSHIP}, so it never leaks
 * into role roll-ups, the candidate-group listing, or the tenant-owner surfaces. Mirrors
 * {@link CandidateGroupOwnership}, which does the same for candidate groups.
 *
 * <p><b>Fallback is the point.</b> {@link #approverGroupId} returns the tenant owner group when a
 * capability has no resource owners, so an approval task can never strand: a fresh org that has
 * configured nothing still routes every request to its owners, exactly as before.
 *
 * <p>Membership is checked with {@code GroupQuery.groupId(g).groupMember(u)} — NOT
 * {@code UserQuery.userId(u).memberOfGroup(g)}, which is broken in the engine (ignores the group
 * filter and returns 1 for any user). See {@link TenantOwnership} for the full story on that trap.
 */
public final class CapabilityOwnership {

    /** Group type for the per-capability resource-owner binding — intentionally isolated. */
    public static final String GROUP_TYPE = "CAP_OWNERSHIP";

    private static final String PREFIX = "capowner:";

    private CapabilityOwnership() {}

    /** The group holding the resource owners of {@code capabilityCode} within {@code tenantId}. */
    public static String ownerGroupId(String tenantId, String capabilityCode) {
        return PREFIX + tenantId + ":" + normalize(capabilityCode);
    }

    /**
     * The group an approval task should route to: the capability's resource owners when it has
     * any, else the tenant owner group. Never returns null for a valid tenant.
     */
    public static String approverGroupId(IdentityService identity, String tenantId, String capabilityCode) {
        String gid = ownerGroupId(tenantId, capabilityCode);
        return hasMembers(identity, gid) ? gid : TenantOwnership.groupId(tenantId);
    }

    /** Is {@code userId} a resource owner of {@code capabilityCode} in {@code tenantId}? */
    public static boolean isOwner(IdentityService identity, String userId, String tenantId, String capabilityCode) {
        if (userId == null || tenantId == null || capabilityCode == null) return false;
        return identity.createGroupQuery()
                .groupId(ownerGroupId(tenantId, capabilityCode))
                .groupMember(userId)
                .count() > 0;
    }

    /** The user ids owning {@code capabilityCode} (memberOfGroup WITHOUT userId is the safe form). */
    public static List<String> ownerIds(IdentityService identity, String tenantId, String capabilityCode) {
        return identity.createUserQuery()
                .memberOfGroup(ownerGroupId(tenantId, capabilityCode))
                .list()
                .stream()
                .map(u -> u.getId())
                .toList();
    }

    /**
     * Does {@code userId} own ANY capability in {@code tenantId}?
     *
     * <p>Used to authorize the approval queue: a resource owner must be able to open it and see
     * an empty list on a quiet day, so "your queue is empty" and "you may not look" have to be
     * distinguishable without inspecting individual requests.
     */
    public static boolean ownsAny(IdentityService identity, String userId, String tenantId) {
        if (userId == null || tenantId == null) return false;
        String prefix = PREFIX + tenantId + ":";
        return identity.createGroupQuery().groupMember(userId).list().stream()
                .anyMatch(g -> g.getId() != null && g.getId().startsWith(prefix));
    }

    /** Make {@code userId} a resource owner (idempotent; creates the group on first use). */
    public static void grant(IdentityService identity, String userId, String tenantId, String capabilityCode) {
        ensureGroup(identity, tenantId, capabilityCode);
        if (!isOwner(identity, userId, tenantId, capabilityCode)) {
            identity.createMembership(userId, ownerGroupId(tenantId, capabilityCode));
        }
    }

    /** Remove {@code userId} as a resource owner (best-effort; no-op if they aren't one). */
    public static void revoke(IdentityService identity, String userId, String tenantId, String capabilityCode) {
        try {
            identity.deleteMembership(userId, ownerGroupId(tenantId, capabilityCode));
        } catch (RuntimeException ignored) {
            /* not a member / already gone */
        }
    }

    /** True when the group exists AND has at least one member (an empty group must not capture tasks). */
    private static boolean hasMembers(IdentityService identity, String groupId) {
        try {
            return identity.createUserQuery().memberOfGroup(groupId).count() > 0;
        } catch (RuntimeException e) {
            return false; // group never created
        }
    }

    private static void ensureGroup(IdentityService identity, String tenantId, String capabilityCode) {
        String gid = ownerGroupId(tenantId, capabilityCode);
        if (identity.createGroupQuery().groupId(gid).count() == 0) {
            Group g = identity.newGroup(gid);
            g.setName("Resource owners of " + normalize(capabilityCode));
            g.setType(GROUP_TYPE);
            identity.saveGroup(g);
        }
    }

    /** Capability codes are uppercase by convention; normalize so group ids can't fork on casing. */
    private static String normalize(String capabilityCode) {
        return capabilityCode == null ? "" : capabilityCode.trim().toUpperCase(Locale.ROOT);
    }
}
