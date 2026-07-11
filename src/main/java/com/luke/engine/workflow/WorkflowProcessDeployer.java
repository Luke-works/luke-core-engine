package com.luke.engine.workflow;

import org.finos.fluxnova.bpm.engine.RepositoryService;
import org.finos.fluxnova.bpm.engine.repository.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deploys a compiled workflow BPMN to Camunda / CIBSeven, tenant-scoped. Unlike the
 * static form/phone processes (classpath resources deployed at boot), workflow
 * processes are generated per definition and deployed on <b>publish</b>. Idempotent
 * via duplicate filtering — re-publishing identical BPMN is a no-op (no new version).
 *
 * <p>Mirrors {@code FormProcessDeployer}, but sources the BPMN from a compiled string
 * ({@link WorkflowCompiler}) rather than the classpath.
 */
@Component
public class WorkflowProcessDeployer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowProcessDeployer.class);

    private final RepositoryService repositoryService;

    public WorkflowProcessDeployer(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    /**
     * Deploy {@code bpmnXml} for {@code tenantId}. The resource name ends in
     * {@code .bpmn} so the engine parses it as BPMN. Returns the deployment id.
     */
    public String deploy(String tenantId, String processId, String bpmnXml) {
        Deployment deployment = repositoryService.createDeployment()
                .name("workflow:" + processId)
                .tenantId(tenantId)
                .enableDuplicateFiltering(true)
                .addString(processId + ".bpmn", bpmnXml)
                .deploy();
        log.info("Deployed workflow process {} for tenant {} (deployment {})", processId, tenantId, deployment.getId());
        return deployment.getId();
    }
}
