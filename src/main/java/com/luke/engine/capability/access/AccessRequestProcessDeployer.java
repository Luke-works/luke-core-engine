package com.luke.engine.capability.access;

import com.luke.engine.config.BootCoordinator;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.identity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deploys the access-request approval BPMN per tenant. Camunda auto-deployment is off, so this is
 * explicit: every tenant gets its own tenant-scoped copy (fitting the tenant-scoped authorization
 * model). Idempotent via duplicate filtering — re-deploying identical resources is a no-op — so
 * calling {@link #deployFor} on every org creation and on every boot is safe. Mirrors
 * {@code SignatureProcessDeployer}.
 */
@Component
public class AccessRequestProcessDeployer {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestProcessDeployer.class);

    /** Classpath BPMN (lives at resources root). */
    private static final String RESOURCE = "AccessRequestApprovalProcess.bpmn";
    private static final String DEPLOYMENT_NAME = "access-request-approval";

    private final RepositoryService repositoryService;
    private final IdentityService identityService;
    private final BootCoordinator bootCoordinator;

    public AccessRequestProcessDeployer(RepositoryService repositoryService, IdentityService identityService,
            BootCoordinator bootCoordinator) {
        this.repositoryService = repositoryService;
        this.identityService = identityService;
        this.bootCoordinator = bootCoordinator;
    }

    /** Deploy the approval process for a single tenant (no-op if unchanged). */
    public void deployFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        try {
            repositoryService.createDeployment()
                    .name(DEPLOYMENT_NAME)
                    .tenantId(tenantId)
                    .enableDuplicateFiltering(true)
                    .addClasspathResource(RESOURCE)
                    .deploy();
            log.info("Ensured access-request-approval process for tenant {}", tenantId);
        } catch (Exception e) {
            // Best-effort: a deploy failure must not break org creation / boot.
            log.warn("Could not deploy access-request-approval process for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** On startup, backfill the process for every existing tenant. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingTenants() {
        bootCoordinator.runExclusive("access-request-approval-backfill", this::backfillExistingTenantsExclusive);
    }

    private void backfillExistingTenantsExclusive() {
        try {
            for (Tenant t : identityService.createTenantQuery().list()) {
                deployFor(t.getId());
            }
        } catch (Exception e) {
            log.warn("access-request-approval backfill skipped: {}", e.getMessage());
        }
    }
}
