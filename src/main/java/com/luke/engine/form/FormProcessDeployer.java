package com.luke.engine.form;

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
 * Deploys the generic form-intake BPMN per tenant. Camunda auto-deployment is
 * off, so this is explicit: every tenant gets its own tenant-scoped copy of the
 * process (fitting the tenant-scoped authorization model). Idempotent via
 * duplicate filtering — re-deploying identical resources is a no-op (no new
 * version), so calling {@link #deployFor} on every org creation and on every
 * boot (backfill) is safe.
 */
@Component
public class FormProcessDeployer {

    private static final Logger log = LoggerFactory.getLogger(FormProcessDeployer.class);

    /** Classpath BPMN (lives at resources root). */
    private static final String RESOURCE = "FormSubmissionIntakeProcess.bpmn";
    private static final String DEPLOYMENT_NAME = "form-submission-intake";

    private final RepositoryService repositoryService;
    private final IdentityService identityService;
    private final BootCoordinator bootCoordinator;

    public FormProcessDeployer(RepositoryService repositoryService, IdentityService identityService,
            BootCoordinator bootCoordinator) {
        this.repositoryService = repositoryService;
        this.identityService = identityService;
        this.bootCoordinator = bootCoordinator;
    }

    /** Deploy the form-intake process for a single tenant (no-op if unchanged). */
    public void deployFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        try {
            repositoryService.createDeployment()
                    .name(DEPLOYMENT_NAME)
                    .tenantId(tenantId)
                    .enableDuplicateFiltering(true)
                    .addClasspathResource(RESOURCE)
                    .deploy();
            log.info("Ensured form-intake process for tenant {}", tenantId);
        } catch (Exception e) {
            // Best-effort: a deploy failure must not break org creation / boot.
            log.warn("Could not deploy form-intake process for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** On startup, backfill the process for every existing tenant. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingTenants() {
        // #40: serialize the boot backfill across instances so simultaneous deploys for the
        // same tenant don't race (duplicate filtering + the lock make it a clean no-op).
        bootCoordinator.runExclusive("form-intake-backfill", this::backfillExistingTenantsExclusive);
    }

    private void backfillExistingTenantsExclusive() {
        try {
            for (Tenant t : identityService.createTenantQuery().list()) {
                deployFor(t.getId());
            }
        } catch (Exception e) {
            log.warn("form-intake backfill skipped: {}", e.getMessage());
        }
    }
}
