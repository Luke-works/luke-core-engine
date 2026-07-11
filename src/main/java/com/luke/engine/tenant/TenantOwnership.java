package com.luke.engine.tenant;

import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;

/**
 * The single source of truth for "who administers <em>this</em> tenant".
 *
 * <p><b>Why this exists.</b> Camunda role groups ({@code tenant-admin}, {@code tenant-user}, …)
 * are <em>global</em> — a membership is not scoped to a tenant. So checking "is this user a
 * {@code tenant-admin}?" answers "…of <em>any</em> org" and lets someone who is admin of org A,
 * but merely an invited member of org B, administer org B too (cross-tenant privilege
 * escalation). The org-admin API and access-request approvals must instead ask "is this user an
 * owner <em>of the active tenant</em>?".
 *
 * <p><b>The binding.</b> Ownership is recorded as membership of a per-tenant group whose id is
 * {@code owner:<tenantId>} and whose {@link #GROUP_TYPE type} is a dedicated {@code OWNERSHIP}
 * — deliberately neither {@code ROLE} nor {@code ORGANIZATIONAL}, so it never leaks into role
 * roll-ups or the ABAC candidate-group surfaces. The global {@code tenant-admin} ROLE group is
 * still written (for role display and Camunda's own authorizations); this scoped group is what
 * every <em>authorization</em> decision reads.
 *
 * <p>Owner ≙ admin of the tenant (a tenant may have several — "co-owners"); the creator is
 * seeded as the first owner and ownership is transferable by granting/revoking this membership.
 */
public final class TenantOwnership {

    /** Group type for the per-tenant ownership binding — intentionally not ROLE/ORGANIZATIONAL. */
    public static final String GROUP_TYPE = "OWNERSHIP";

    private static final String PREFIX = "owner:";

    private TenantOwnership() {}

    /** The scoped group id that records ownership of {@code tenantId}. */
    public static String groupId(String tenantId) {
        return PREFIX + tenantId;
    }

    // IMPORTANT: membership is checked via GroupQuery.groupId(g).groupMember(u), NOT
    // UserQuery.userId(u).memberOfGroup(g). The latter is BROKEN in CIBSeven — combined with
    // userId in a count() it ignores the group filter and returns 1 for any existing user, which
    // silently made isOwner() true for everyone AND made grant()'s idempotency guard skip the
    // createMembership for everyone (owner groups stayed empty). Net effect: the scoped-ownership
    // authorization was completely bypassed — any tenant member could administer the tenant.
    // The group-query form below is verified to evaluate membership correctly.
    private static boolean isMemberOfOwnerGroup(IdentityService identity, String userId, String tenantId) {
        return identity.createGroupQuery().groupId(groupId(tenantId)).groupMember(userId).count() > 0;
    }

    /** Is {@code userId} an owner (admin) of {@code tenantId}? */
    public static boolean isOwner(IdentityService identity, String userId, String tenantId) {
        if (userId == null || tenantId == null) return false;
        return isMemberOfOwnerGroup(identity, userId, tenantId);
    }

    /** How many owners does {@code tenantId} have? Used for the last-owner guard. */
    public static long ownerCount(IdentityService identity, String tenantId) {
        // memberOfGroup WITHOUT userId is fine (only the userId+memberOfGroup combination is broken).
        return identity.createUserQuery().memberOfGroup(groupId(tenantId)).count();
    }

    /** Make {@code userId} an owner of {@code tenantId} (idempotent; creates the group on first use). */
    public static void grant(IdentityService identity, String userId, String tenantId) {
        ensureGroup(identity, tenantId);
        if (!isMemberOfOwnerGroup(identity, userId, tenantId)) {
            identity.createMembership(userId, groupId(tenantId));
        }
    }

    /** Remove {@code userId} as an owner of {@code tenantId} (best-effort; no-op if not an owner). */
    public static void revoke(IdentityService identity, String userId, String tenantId) {
        try {
            identity.deleteMembership(userId, groupId(tenantId));
        } catch (RuntimeException ignored) {
            /* not a member / already gone */
        }
    }

    private static void ensureGroup(IdentityService identity, String tenantId) {
        String gid = groupId(tenantId);
        if (identity.createGroupQuery().groupId(gid).count() == 0) {
            Group g = identity.newGroup(gid);
            g.setName("Owners of " + tenantId);
            g.setType(GROUP_TYPE);
            identity.saveGroup(g);
        }
    }
}
