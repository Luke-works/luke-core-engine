package com.luke.engine.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luke.engine.admin.OrgAdminController;
import com.luke.engine.tenant.TenantOwnership;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * #37 end-to-end: a real privileged mutation writes an audit event through the wired controller, and
 * the read API enforces scope — a platform operator sees any tenant (and the cross-tenant view), a
 * tenant owner sees only their own tenant, and a plain member is forbidden.
 */
@SpringBootTest
class AuditTrailFunctionalTest {

    private static final String CAMUNDA_ADMIN = "camunda-admin";
    private static final String PW = "audit-pw";

    @Autowired private IdentityService identity;
    @Autowired private AdminAuditService audit;
    @Autowired private AuditEventRepository repository;
    @Autowired private AuditController auditController;
    @Autowired private OrgAdminController orgAdmin;

    private final List<String> users = new ArrayList<>();
    private final List<String> tenants = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Audit rows are append-only (no delete path) — they're keyed by unique per-test tenant ids,
        // so leftovers can't affect another test. We only tear down the identity fixtures.
        for (String u : users) {
            for (Group g : identity.createGroupQuery().groupMember(u).list()) {
                try { identity.deleteMembership(u, g.getId()); } catch (RuntimeException ignore) { }
            }
            for (var t : identity.createTenantQuery().userMember(u).list()) {
                try { identity.deleteTenantUserMembership(t.getId(), u); } catch (RuntimeException ignore) { }
            }
            try { identity.deleteUser(u); } catch (RuntimeException ignore) { }
        }
        for (String t : tenants) {
            try { identity.deleteTenant(t); } catch (RuntimeException ignore) { }
        }
    }

    /* ── fixtures ─────────────────────────────────────────────────────── */

    private String user(String prefix) {
        String id = prefix + "-" + UUID.randomUUID();
        User u = identity.newUser(id);
        u.setPassword(PW);
        u.setEmail(id + "@example.com");
        identity.saveUser(u);
        users.add(id);
        return id;
    }

    private String operator() {
        String id = user("aud-op");
        if (identity.createGroupQuery().groupId(CAMUNDA_ADMIN).count() == 0) {
            Group g = identity.newGroup(CAMUNDA_ADMIN);
            g.setName("Camunda Admins");
            identity.saveGroup(g);
        }
        identity.createMembership(id, CAMUNDA_ADMIN);
        return id;
    }

    private String tenant() {
        String id = "AUD-T-" + UUID.randomUUID();
        identity.saveTenant(identity.newTenant(id));
        tenants.add(id);
        return id;
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + PW).getBytes(StandardCharsets.UTF_8));
    }

    private static long total(Map<String, Object> body) {
        return (Long) body.get("totalElements");
    }

    /* ── tests ────────────────────────────────────────────────────────── */

    @Test
    void privilegedMutationWritesAnAuditEventThroughTheWiredController() {
        String op = operator();
        String tenant = tenant();

        // A real wired endpoint (candidate-group create) driven as an operator.
        orgAdmin.createCandidateGroup(basic(op), tenant, new OrgAdminController.GroupBody("Audit Team"));

        Page<AuditEvent> events = repository.findByTenantIdOrderByCreatedAtDesc(tenant, PageRequest.of(0, 10));
        assertThat(events.getContent()).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("candidate_group.create");
            assertThat(e.getTargetType()).isEqualTo("candidate_group");
            assertThat(e.getActorId()).isEqualTo(op);
            assertThat(e.isActorOperator()).isTrue();
            assertThat(e.getTenantId()).isEqualTo(tenant);
        });
    }

    @Test
    void operatorAndOwnerCanReadButAPlainMemberCannot() {
        String op = operator();
        String tenant = tenant();
        String owner = user("aud-owner");
        identity.createTenantUserMembership(tenant, owner);
        TenantOwnership.grant(identity, owner, tenant);
        String member = user("aud-member");
        identity.createTenantUserMembership(tenant, member);

        audit.record("user.create", "user", "x", tenant, op, true);
        audit.record("capability.grant", "capability", "FORMS", tenant, owner, false);

        // Operator sees the tenant's events.
        assertThat(total(auditController.tenantEvents(basic(op), tenant, null, 0, 50))).isEqualTo(2L);
        // Owner sees their own tenant's events.
        assertThat(total(auditController.tenantEvents(basic(owner), tenant, null, 0, 50))).isEqualTo(2L);

        // A plain member (not an owner) is forbidden from the tenant view.
        assertThatThrownBy(() -> auditController.tenantEvents(basic(member), tenant, null, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // The cross-tenant view is operator-only — an owner is not an operator.
        assertThatThrownBy(() -> auditController.allEvents(basic(owner), null, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // Operator can use the cross-tenant view.
        assertThat(total(auditController.allEvents(basic(op), null, 0, 50))).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void actionFilterNarrowsResults() {
        String op = operator();
        String tenant = tenant();
        audit.record("user.create", "user", "a", tenant, op, true);
        audit.record("capability.grant", "capability", "FORMS", tenant, op, true);

        assertThat(total(auditController.tenantEvents(basic(op), tenant, "capability.grant", 0, 50))).isEqualTo(1L);
    }

    @Test
    void unauthenticatedReadIsRejected() {
        assertThatThrownBy(() -> auditController.tenantEvents(null, "AUD-T-none", null, 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
