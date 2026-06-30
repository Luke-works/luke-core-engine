package com.luke.engine.capability.phone;

import com.luke.engine.config.BootCoordinator;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.identity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deploys the {@code PhoneCallProcess} BPMN per tenant. Camunda auto-deployment is off, so this is
 * explicit: every tenant gets its own tenant-scoped copy of the process (fitting the tenant-scoped
 * authorization model). Idempotent via duplicate filtering — re-deploying identical resources is a
 * no-op. Mirrors {@code SignatureProcessDeployer}.
 */
@Component
public class PhoneProcessDeployer {

    private static final Logger log = LoggerFactory.getLogger(PhoneProcessDeployer.class);

    private static final String RESOURCE = "PhoneCallProcess.bpmn";
    private static final String DEPLOYMENT_NAME = "phone-call";

    private final RepositoryService repositoryService;
    private final IdentityService identityService;
    private final BootCoordinator bootCoordinator;

    public PhoneProcessDeployer(RepositoryService repositoryService, IdentityService identityService,
            BootCoordinator bootCoordinator) {
        this.repositoryService = repositoryService;
        this.identityService = identityService;
        this.bootCoordinator = bootCoordinator;
    }

    /** Deploy the phone-call process for a single tenant (no-op if unchanged). */
    public void deployFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        try {
            repositoryService.createDeployment()
                    .name(DEPLOYMENT_NAME)
                    .tenantId(tenantId)
                    .enableDuplicateFiltering(true)
                    .addClasspathResource(RESOURCE)
                    .deploy();
            log.info("Ensured phone-call process for tenant {}", tenantId);
        } catch (Exception e) {
            log.warn("Could not deploy phone-call process for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** On startup, backfill the process for every existing tenant. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingTenants() {
        bootCoordinator.runExclusive("phone-call-backfill", this::backfillExistingTenantsExclusive);
    }

    private void backfillExistingTenantsExclusive() {
        try {
            for (Tenant t : identityService.createTenantQuery().list()) {
                deployFor(t.getId());
            }
        } catch (Exception e) {
            log.warn("phone-call backfill skipped: {}", e.getMessage());
        }
    }
}
