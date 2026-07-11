package com.luke.engine.tenancy;

import java.util.ArrayList;
import java.util.List;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.RuntimeService;
import org.finos.fluxnova.bpm.engine.TaskService;
import org.finos.fluxnova.bpm.engine.identity.Group;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.finos.fluxnova.bpm.engine.impl.identity.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator-only cross-tenant metrics for the tenancy dashboard (#39).
 *
 * <p>Replaces a browser-side O(N) fan-out — six {@code engine-rest /count} calls per
 * tenant, capped at 200 tenants — with ONE server call that lists every tenant and
 * counts its users, definitions, running instances, incidents, deployments and open
 * tasks in-process via the engine query API. The per-tenant queries still run, but
 * in-process (no HTTP/auth overhead, no thundering herd) and with no tenant cap.
 *
 * <p>Restricted to operators (parent-cluster member or {@code camunda-admin}) because
 * it reads across every tenant. Authentication + tenant scoping is applied upstream by
 * {@link com.luke.engine.config.ApiAuthFilter} (Basic + gateway-Bearer) for
 * {@code /api/tenancy/*}; this controller adds the operator authorization check.
 */
@RestController
@RequestMapping("/api/tenancy")
public class TenancyMetricsController {

    private static final Logger log = LoggerFactory.getLogger(TenancyMetricsController.class);
    private static final String CAMUNDA_ADMIN_GROUP = "camunda-admin";

    private final IdentityService identity;
    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final TaskService tasks;

    @Value("${luke.tenant.parent-cluster-id:parent_cluster}")
    private String parentClusterId;

    public TenancyMetricsController(IdentityService identity, RuntimeService runtime,
                                    RepositoryService repository, TaskService tasks) {
        this.identity = identity;
        this.runtime = runtime;
        this.repository = repository;
        this.tasks = tasks;
    }

    public record TenantRef(String id, String name) {}

    public record TenantMetrics(TenantRef tenant, long users, long definitions,
                                long runningInstances, long incidents, long deployments, long openTasks) {}

    public record Totals(long tenants, long users, long definitions,
                         long runningInstances, long incidents, long deployments, long openTasks) {}

    public record TenancyMetrics(List<TenantMetrics> tenants, Totals totals) {}

    /** Per-tenant counts plus rolled-up totals, in a single response. */
    @GetMapping("/metrics")
    public TenancyMetrics metrics() {
        requireOperator();

        List<TenantMetrics> perTenant = new ArrayList<>();
        long tUsers = 0, tDefs = 0, tRun = 0, tInc = 0, tDep = 0, tTasks = 0;
        for (Tenant t : identity.createTenantQuery().list()) {
            String id = t.getId();
            long users = identity.createUserQuery().memberOfTenant(id).count();
            long definitions = repository.createProcessDefinitionQuery().tenantIdIn(id).count();
            long running = runtime.createProcessInstanceQuery().tenantIdIn(id).count();
            long incidents = runtime.createIncidentQuery().tenantIdIn(id).count();
            long deployments = repository.createDeploymentQuery().tenantIdIn(id).count();
            long openTasks = tasks.createTaskQuery().tenantIdIn(id).count();
            perTenant.add(new TenantMetrics(new TenantRef(id, t.getName()),
                    users, definitions, running, incidents, deployments, openTasks));
            tUsers += users;
            tDefs += definitions;
            tRun += running;
            tInc += incidents;
            tDep += deployments;
            tTasks += openTasks;
        }
        Totals totals = new Totals(perTenant.size(), tUsers, tDefs, tRun, tInc, tDep, tTasks);
        return new TenancyMetrics(perTenant, totals);
    }

    /** Cross-tenant read — require an operator (parent-cluster member or camunda-admin). */
    private void requireOperator() {
        Authentication auth = identity.getCurrentAuthentication();
        String userId = auth == null ? null : auth.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        boolean operator = identity.createTenantQuery().userMember(userId).list().stream()
                        .anyMatch(t -> parentClusterId.equals(t.getId()))
                || identity.createGroupQuery().groupMember(userId).list().stream()
                        .map(Group::getId).anyMatch(CAMUNDA_ADMIN_GROUP::equals);
        if (!operator) {
            log.warn("Non-operator user '{}' attempted to read cross-tenant metrics", userId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operator rights required");
        }
    }
}
