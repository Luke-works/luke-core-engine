package com.luke.engine.document;

import com.luke.engine.capability.access.CapabilityAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The layered document authZ gate (DOC-4): every doc op passes THREE checks, in order —
 * <ol>
 *   <li><b>tenant</b> — the row's tenant must equal the caller's (else 404, no existence leak);</li>
 *   <li><b>capability</b> — the caller must hold the owning capability (else 403);</li>
 *   <li><b>context</b> — via {@link TaskAccessResolver}: the caller must be able to see this doc's
 *       task/process per its Camunda candidate groups (else 404, no leak).</li>
 * </ol>
 * Context failures are 404 (not 403) so a user outside a task/process can't even learn the doc exists.
 */
@Component
public class DocumentAccessGuard {

    /**
     * FORMS submission documents are gated by the FORMS capability ALONE — not additionally by the
     * DOC-4 task/process candidate-group context. Rationale: the Form Inbox already exposes every
     * tenant submission to anyone with FORMS access, so attachments follow the same boundary (a FORMS
     * reviewer sees the submission's files; an admin reviewing an embed submission with no candidate
     * group still can). Other capabilities (SIGNATURES, EMAIL, …) keep the strict context check.
     */
    private static final String FORMS_CAPABILITY = "FORMS";

    private final CapabilityAccessService capabilities;
    private final TaskAccessResolver taskAccess;

    public DocumentAccessGuard(CapabilityAccessService capabilities, TaskAccessResolver taskAccess) {
        this.capabilities = capabilities;
        this.taskAccess = taskAccess;
    }

    /** Authorize an UPLOAD (no row yet): capability write + context for the requested process/task. */
    public void requireUpload(String tenantId, String userId, String capability,
                              String processRef, String processInstanceId, String taskId) {
        if (!capabilities.isAllowed(tenantId, userId, capability, true)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no access to capability " + capability);
        }
        // FORMS: capability(write) suffices (so an inbox reviewer can "Attach to this task" without
        // being a Camunda candidate). Other capabilities still require task/process upload context.
        if (!FORMS_CAPABILITY.equals(capability)
                && !taskAccess.canUpload(tenantId, userId, processRef, processInstanceId, taskId)) {
            throw notFound();
        }
    }

    /** Authorize a READ of an existing doc (download / metadata): tenant + capability(read) + context. */
    public void requireRead(String tenantId, String userId, Document doc) {
        check(tenantId, userId, doc, false);
    }

    /** Authorize a WRITE/DELETE of an existing doc: tenant + capability(write) + context. */
    public void requireWrite(String tenantId, String userId, Document doc) {
        check(tenantId, userId, doc, true);
    }

    /** Non-throwing read check — used to FILTER list results to what the caller may see. */
    public boolean mayRead(String tenantId, String userId, Document doc) {
        return doc != null
                && doc.getTenantId().equals(tenantId)
                && capabilities.isAllowed(tenantId, userId, doc.getCapability(), false)
                && contextAllows(tenantId, userId, doc);
    }

    private void check(String tenantId, String userId, Document doc, boolean needWrite) {
        if (doc == null || !doc.getTenantId().equals(tenantId)) {
            throw notFound();                                   // tenant mismatch — no leak
        }
        if (!capabilities.isAllowed(tenantId, userId, doc.getCapability(), needWrite)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no access to capability " + doc.getCapability());
        }
        if (!contextAllows(tenantId, userId, doc)) {
            throw notFound();                                   // outside the task/process — no leak
        }
    }

    /** The DOC-4 context layer, with the FORMS carve-out: FORMS docs pass on the capability gate alone;
     *  everything else must satisfy the task/process candidate-group check. */
    private boolean contextAllows(String tenantId, String userId, Document doc) {
        return FORMS_CAPABILITY.equals(doc.getCapability())
                || taskAccess.canAccess(tenantId, userId, doc);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found");
    }
}
