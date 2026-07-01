package com.luke.engine.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The WORKFLOW design-time lifecycle (WF-4), mirroring the forms model:
 *
 * <ul>
 *   <li><b>check-in</b> — snapshot the draft as an immutable {@link WorkflowVersion};
 *       compile best-effort (a compile failure is recorded, never blocks the snapshot).</li>
 *   <li><b>sign-off</b> — mark a version tested; requires a clean compile.</li>
 *   <li><b>publish</b> — deploy a signed-off version to Camunda; gated on sign-off,
 *       a clean compile, and every step's capability being resolvable.</li>
 * </ul>
 *
 * The builder validates for UX; this service re-compiles (server-authoritative) and
 * is the only thing that can deploy a runnable process.
 */
@Service
public class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowVersionRepository versions;
    private final WorkflowCompiler compiler;
    private final WorkflowProcessDeployer deployer;
    private final ObjectMapper mapper;
    private final StepTypeRegistry registry;

    public WorkflowDefinitionService(WorkflowDefinitionRepository definitions,
            WorkflowVersionRepository versions, WorkflowCompiler compiler,
            WorkflowProcessDeployer deployer, ObjectMapper mapper, StepTypeRegistry registry) {
        this.definitions = definitions;
        this.versions = versions;
        this.compiler = compiler;
        this.deployer = deployer;
        this.mapper = mapper;
        this.registry = registry;
    }

    @Transactional
    public WorkflowDefinition create(String tenantId, String name, String description, String draftJson, String user) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setTenantId(tenantId);
        def.setName(name);
        def.setDescription(description);
        def.setDraftJson(draftJson);
        def.setCreatedBy(user);
        def.setUpdatedBy(user);
        return definitions.save(def);
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinition> list(String tenantId) {
        return definitions.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition get(String tenantId, String id) {
        return require(tenantId, id);
    }

    @Transactional(readOnly = true)
    public List<WorkflowVersion> versions(String tenantId, String id) {
        require(tenantId, id);
        return versions.findByDefinitionIdOrderByVersionAsc(id);
    }

    @Transactional
    public WorkflowDefinition updateDraft(String tenantId, String id, String name, String description,
            String draftJson, String user) {
        WorkflowDefinition def = require(tenantId, id);
        if (name != null) def.setName(name);
        if (description != null) def.setDescription(description);
        if (draftJson != null) def.setDraftJson(draftJson);
        def.setUpdatedBy(user);
        return definitions.save(def);
    }

    /** Snapshot the current draft as a new version. Compiles best-effort — errors are stored, not thrown. */
    @Transactional
    public WorkflowVersion checkIn(String tenantId, String id, String user) {
        WorkflowDefinition def = require(tenantId, id);
        int next = def.getLatestVersion() + 1;
        WorkflowVersion v = new WorkflowVersion(id, next, def.getDraftJson(), user);
        try {
            WorkflowDoc doc = mapper.readValue(def.getDraftJson(), WorkflowDoc.class);
            CompileResult r = compiler.compile(doc);
            v.setBpmnXml(r.bpmnXml());
            v.setProcessId(r.processId());
            v.setCompileOk(true);
        } catch (Exception e) {
            v.setCompileOk(false);
            v.setCompileError(e.getMessage());
        }
        def.setLatestVersion(next);
        definitions.save(def);
        return versions.save(v);
    }

    /** Sign off a version (the "tested" gate). Requires a clean compile. */
    @Transactional
    public WorkflowVersion signOff(String tenantId, String id, int version, String user) {
        require(tenantId, id);
        WorkflowVersion v = requireVersion(id, version);
        if (!v.isCompileOk()) {
            throw new WorkflowLifecycleException("Cannot sign off version " + version + " — it does not compile");
        }
        v.setSignedOffAt(LocalDateTime.now());
        v.setSignedOffBy(user);
        return versions.save(v);
    }

    /** Deploy a signed-off version to Camunda. Gated on sign-off + compile + capability resolution. */
    @Transactional
    public WorkflowDefinition publish(String tenantId, String id, int version, String user) {
        WorkflowDefinition def = require(tenantId, id);
        WorkflowVersion v = requireVersion(id, version);
        if (v.getSignedOffAt() == null) {
            throw new WorkflowLifecycleException("Version " + version + " is not signed off");
        }
        if (!v.isCompileOk() || v.getBpmnXml() == null || v.getProcessId() == null) {
            throw new WorkflowLifecycleException("Version " + version + " has no compiled BPMN");
        }
        assertCapabilitiesResolvable(v.getJsonSource());
        deployer.deploy(tenantId, v.getProcessId(), v.getBpmnXml());
        def.setPublishedVersion(version);
        def.setStatus("PUBLISHED");
        def.setUpdatedBy(user);
        return definitions.save(def);
    }

    /** Every action/task node's capability must map to a registered step type. */
    private void assertCapabilitiesResolvable(String json) {
        WorkflowDoc doc;
        try {
            doc = mapper.readValue(json, WorkflowDoc.class);
        } catch (Exception e) {
            throw new WorkflowLifecycleException("Invalid workflow JSON: " + e.getMessage());
        }
        if (doc.nodes() == null) return;
        for (WorkflowNode n : doc.nodes()) {
            String kind = n.kind();
            if (!"action".equals(kind) && !"task".equals(kind)) continue;
            if (n.capability() == null) continue;
            if (registry.resolve(n.capability(), kind).isEmpty()) {
                throw new WorkflowLifecycleException("Node '" + n.id() + "' uses capability '"
                        + n.capability() + "' (" + kind + ") with no registered step type");
            }
        }
    }

    private WorkflowDefinition require(String tenantId, String id) {
        return definitions.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new WorkflowLifecycleException("Workflow definition not found"));
    }

    private WorkflowVersion requireVersion(String id, int version) {
        return versions.findByDefinitionIdAndVersion(id, version)
                .orElseThrow(() -> new WorkflowLifecycleException("Version " + version + " not found"));
    }
}
