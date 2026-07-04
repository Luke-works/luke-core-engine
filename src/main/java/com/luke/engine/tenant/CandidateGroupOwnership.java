package com.luke.engine.tenant;

import java.util.List;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;

/**
 * Per-candidate-group manager binding: who may manage the membership of ONE tenant candidate group
 * ({@code <tenantId>:<name>}), without being a full tenant owner. Lets a tenant owner delegate
 * "who's in the sales group" to a lead, scoped to that group only.
 *
 * <p>Managers are members of a dedicated group {@code cgowner:<candidateGroupId>} whose {@link #GROUP_TYPE
 * type} is {@code CG_OWNERSHIP} — deliberately not {@code ROLE}/{@code ORGANIZATIONAL}/{@code OWNERSHIP},
 * so it never leaks into role roll-ups, the ABAC candidate-group listing, or the tenant-owner surfaces.
 *
 * <p>Membership is checked with {@code GroupQuery.groupId(g).groupMember(u)} — NOT
 * {@code UserQuery.userId(u).memberOfGroup(g)}, which is broken in CIBSeven (ignores the group filter
 * and returns 1 for any user). See {@link TenantOwnership} for the full story on that trap.
 */
public final class CandidateGroupOwnership {

    /** Group type for the per-candidate-group manager binding — intentionally isolated. */
    public static final String GROUP_TYPE = "CG_OWNERSHIP";

    private static final String PREFIX = "cgowner:";

    private CandidateGroupOwnership() {}

    /** The group holding the managers of {@code candidateGroupId} (e.g. {@code "<tenant>:sales"}). */
    public static String managerGroupId(String candidateGroupId) {
        return PREFIX + candidateGroupId;
    }

    /** Is {@code userId} a manager of {@code candidateGroupId}? */
    public static boolean isManager(IdentityService identity, String userId, String candidateGroupId) {
        if (userId == null || candidateGroupId == null) return false;
        return identity.createGroupQuery().groupId(managerGroupId(candidateGroupId)).groupMember(userId).count() > 0;
    }

    /** The user ids managing {@code candidateGroupId} (memberOfGroup WITHOUT userId is the safe form). */
    public static List<String> managerIds(IdentityService identity, String candidateGroupId) {
        return identity.createUserQuery().memberOfGroup(managerGroupId(candidateGroupId)).list()
                .stream().map(u -> u.getId()).toList();
    }

    /** Make {@code userId} a manager of {@code candidateGroupId} (idempotent; creates the group on first use). */
    public static void grant(IdentityService identity, String userId, String candidateGroupId) {
        ensureGroup(identity, candidateGroupId);
        if (!isManager(identity, userId, candidateGroupId)) {
            identity.createMembership(userId, managerGroupId(candidateGroupId));
        }
    }

    /** Remove {@code userId} as a manager of {@code candidateGroupId} (best-effort; no-op if not one). */
    public static void revoke(IdentityService identity, String userId, String candidateGroupId) {
        try {
            identity.deleteMembership(userId, managerGroupId(candidateGroupId));
        } catch (RuntimeException ignored) {
            /* not a member / already gone */
        }
    }

    private static void ensureGroup(IdentityService identity, String candidateGroupId) {
        String gid = managerGroupId(candidateGroupId);
        if (identity.createGroupQuery().groupId(gid).count() == 0) {
            Group g = identity.newGroup(gid);
            g.setName("Managers of " + candidateGroupId);
            g.setType(GROUP_TYPE);
            identity.saveGroup(g);
        }
    }
}
