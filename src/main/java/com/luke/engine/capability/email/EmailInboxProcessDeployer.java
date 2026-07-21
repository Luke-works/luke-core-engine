package com.luke.engine.capability.email;

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
 * Deploys the default per-tenant <b>email-inbox</b> BPMN — the EMAIL analogue of
 * {@link com.luke.engine.form.FormProcessDeployer} (forms), and the signature / phone
 * process deployers. Every tenant gets its own tenant-scoped copy of
 * {@code EmailInboxProcess}, started by the public inbound webhook for each message arriving
 * at a registered inbound box (surfacing it as a "Review inbound email" task).
 *
 * <p>Idempotent via duplicate filtering — safe to call on every org creation and every boot.
 */
@Component
public class EmailInboxProcessDeployer {

    private static final Logger log = LoggerFactory.getLogger(EmailInboxProcessDeployer.class);

    /** Classpath BPMN (resources root). */
    private static final String RESOURCE = "EmailInboxProcess.bpmn";
    private static final String DEPLOYMENT_NAME = "email-inbox";

    private final RepositoryService repositoryService;
    private final IdentityService identityService;
    private final BootCoordinator bootCoordinator;

    public EmailInboxProcessDeployer(RepositoryService repositoryService, IdentityService identityService,
            BootCoordinator bootCoordinator) {
        this.repositoryService = repositoryService;
        this.identityService = identityService;
        this.bootCoordinator = bootCoordinator;
    }

    /** Deploy the email-inbox process for a single tenant (no-op if unchanged). */
    public void deployFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        try {
            repositoryService.createDeployment()
                    .name(DEPLOYMENT_NAME)
                    .tenantId(tenantId)
                    .enableDuplicateFiltering(true)
                    .addClasspathResource(RESOURCE)
                    .deploy();
            log.info("Ensured email-inbox process for tenant {}", tenantId);
        } catch (Exception e) {
            // Best-effort: a deploy failure must not break org creation / boot.
            log.warn("Could not deploy email-inbox process for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /** On startup, backfill the process for every existing tenant (serialized across instances). */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingTenants() {
        bootCoordinator.runExclusive("email-inbox-backfill", this::backfillExistingTenantsExclusive);
    }

    private void backfillExistingTenantsExclusive() {
        try {
            for (Tenant t : identityService.createTenantQuery().list()) {
                deployFor(t.getId());
            }
        } catch (Exception e) {
            log.warn("email-inbox backfill skipped: {}", e.getMessage());
        }
    }
}
