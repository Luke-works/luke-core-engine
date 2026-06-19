package com.luke.engine.capability.form;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Form instances: a concrete runtime occurrence of a definition version, with a
 * lifecycle state (see {@link FormInstanceStates}). Covers a public submission,
 * a prefilled invitation, and a task-bound fill. Tenant-scoped via
 * {@code X-Tenant-Id}.
 *
 * <p>Note: read-by-token here is still tenant-scoped — the unauthenticated
 * public surface (UC1/UC2) is a separate, deferred concern.
 */
@RestController
@RequestMapping("/api/form-instances")
public class FormInstanceController {

    private final FormInstanceRepository instances;
    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;
    private final FormSubmissionService submissions;

    public FormInstanceController(FormInstanceRepository instances,
                                  FormDefinitionRepository forms,
                                  FormVersionRepository versions,
                                  FormSubmissionService submissions) {
        this.instances = instances;
        this.forms = forms;
        this.versions = versions;
        this.submissions = submissions;
    }

    /* ── request bodies ─────────────────────────────────────── */
    public record CreateInstance(String definitionCode, Integer version,
                                 Map<String, Object> prefill, Map<String, Object> recipient,
                                 Map<String, Object> context, Long expiresAt) {}
    public record DataBody(Map<String, Object> data) {}
    public record StateBody(String state, String reason) {}

    /* ── create / read ──────────────────────────────────────── */

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestHeader("X-Tenant-Id") String tenantId,
                                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @RequestBody CreateInstance body) {
        requireTenant(tenantId);
        if (body.definitionCode() == null || body.definitionCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "definitionCode is required");
        }
        FormDefinition form = forms.findByTenantIdAndCode(tenantId, body.definitionCode())
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> notFound("Unknown form code: " + body.definitionCode()));

        int version = body.version() != null ? body.version()
                : (form.getPublishedVersion() != null ? form.getPublishedVersion()
                    : fail(HttpStatus.CONFLICT, "Form " + form.getCode() + " has no published version to instantiate"));
        // validate the version exists
        FormVersion artifact = versions.findByFormIdAndVersion(form.getId(), version)
                .orElseThrow(() -> notFound("Unknown version v" + version + " for " + form.getCode()));

        FormInstance inst = new FormInstance();
        inst.setTenantId(tenantId);
        inst.setToken(uniqueToken());
        inst.setDefinitionCode(form.getCode());
        inst.setVersion(version);
        inst.setState(FormInstanceStates.CREATED);
        inst.setPrefill(body.prefill());
        inst.setRecipient(body.recipient());
        inst.setContext(body.context());
        inst.setCreatedBy(userId);
        if (body.expiresAt() != null) inst.setExpiresAt(toLocal(body.expiresAt()));
        instances.save(inst);
        return view(inst, artifact.getSchema());
    }

    @GetMapping
    public List<FormInstance> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestParam(required = false) String state,
                                   @RequestParam(required = false) String definitionCode) {
        requireTenant(tenantId);
        if (definitionCode != null) return instances.findByTenantIdAndDefinitionCodeOrderByCreatedAtDesc(tenantId, definitionCode);
        if (state != null) return instances.findByTenantIdAndStateOrderByCreatedAtDesc(tenantId, state);
        return instances.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** Full view incl. the resolved schema, for rendering. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormInstance inst = load(tenantId, id);
        return view(inst, schemaFor(tenantId, inst));
    }

    @GetMapping("/by-token/{token}")
    public Map<String, Object> getByToken(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String token) {
        requireTenant(tenantId);
        FormInstance inst = instances.findByTokenAndTenantId(token, tenantId)
                .orElseThrow(() -> notFound("Unknown instance token"));
        return view(inst, schemaFor(tenantId, inst));
    }

    /* ── data + lifecycle ───────────────────────────────────── */

    /** Partial save (autosave). Moves an open instance to IN_PROGRESS. */
    @PatchMapping("/{id}")
    public Map<String, Object> save(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @PathVariable String id, @RequestBody DataBody body) {
        FormInstance inst = load(tenantId, id);
        if (!FormInstanceStates.isOpen(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instance is not open for edits (state " + inst.getState() + ")");
        }
        rejectIfExpired(inst);
        inst.setData(merge(inst.getData(), body.data()));
        inst.setState(FormInstanceStates.IN_PROGRESS);
        instances.save(inst);
        return view(inst, schemaFor(tenantId, inst));
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@RequestHeader("X-Tenant-Id") String tenantId,
                                      @PathVariable String id, @RequestBody(required = false) DataBody body) {
        FormInstance inst = load(tenantId, id);
        if (!FormInstanceStates.isOpen(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instance cannot be submitted from state " + inst.getState());
        }
        rejectIfExpired(inst);
        // Atomic: mark SUBMITTED + enqueue the process start in ONE transaction; the
        // outbox consumer starts the Camunda process off-thread (durable, no HTTP hop).
        submissions.submit(inst, body != null ? body.data() : null);
        return view(inst, schemaFor(tenantId, inst));
    }

    /** Reject (and record) an expired instance: an open-but-past-expiry form must not
     *  accept edits or submissions (#54). Marks it EXPIRED so it leaves the open set. */
    private void rejectIfExpired(FormInstance inst) {
        if (inst.isExpired()) {
            inst.setState(FormInstanceStates.EXPIRED);
            instances.save(inst);
            throw new ResponseStatusException(HttpStatus.GONE, "This form has expired.");
        }
    }

    /**
     * Re-attempt the process start for a submitted instance whose process never
     * started (or failed). Records the fresh outcome — so it both recovers the
     * submission and surfaces the explicit error if it fails again.
     */
    @PostMapping("/{id}/retry-process")
    public Map<String, Object> retryProcess(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormInstance inst = load(tenantId, id);
        if (!FormInstanceStates.SUBMITTED.equals(inst.getState()) && !FormInstanceStates.PROCESSED.equals(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a submitted instance can start a process (state " + inst.getState() + ")");
        }
        Object existing = inst.getContext() != null ? inst.getContext().get("processInstanceId") : null;
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A process is already running for this submission");
        }
        // Re-enqueue; the outbox consumer retries the start off-thread. The outcome
        // lands on the instance context (processStartStatus / processInstanceId).
        submissions.reEnqueue(inst);
        return Map.of("status", "QUEUED", "processInstanceId", "", "error", "");
    }

    /** Generic guarded state transition. */
    @PutMapping("/{id}/state")
    public FormInstance setState(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @PathVariable String id, @RequestBody StateBody body) {
        FormInstance inst = load(tenantId, id);
        return transition(inst, body.state());
    }

    @PostMapping("/{id}/send")
    public FormInstance send(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return transition(load(tenantId, id), FormInstanceStates.SENT);
    }

    @PostMapping("/{id}/cancel")
    public FormInstance cancel(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return transition(load(tenantId, id), FormInstanceStates.CANCELLED);
    }

    @PostMapping("/{id}/processed")
    public FormInstance processed(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return transition(load(tenantId, id), FormInstanceStates.PROCESSED);
    }

    /* ── helpers ────────────────────────────────────────────── */

    private FormInstance transition(FormInstance inst, String to) {
        if (to == null || !FormInstanceStates.isKnown(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown state: " + to);
        }
        if (!FormInstanceStates.canTransition(inst.getState(), to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Illegal transition " + inst.getState() + " → " + to);
        }
        inst.setState(to);
        if (FormInstanceStates.SUBMITTED.equals(to) && inst.getSubmittedAt() == null) {
            inst.setSubmittedAt(LocalDateTime.now());
        }
        return instances.save(inst);
    }

    private FormInstance load(String tenantId, String id) {
        requireTenant(tenantId);
        return instances.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown instance: " + id));
    }

    /** Resolve the schema string the instance pins. */
    private String schemaFor(String tenantId, FormInstance inst) {
        FormDefinition form = forms.findByTenantIdAndCode(tenantId, inst.getDefinitionCode())
                .orElseThrow(() -> notFound("Form code gone: " + inst.getDefinitionCode()));
        return versions.findByFormIdAndVersion(form.getId(), inst.getVersion())
                .map(FormVersion::getSchema)
                .orElseThrow(() -> notFound("Version v" + inst.getVersion() + " gone for " + inst.getDefinitionCode()));
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> add) {
        Map<String, Object> out = new HashMap<>(base == null ? Map.of() : base);
        if (add != null) out.putAll(add);
        return out;
    }

    private Map<String, Object> view(FormInstance inst, String schema) {
        Map<String, Object> m = new HashMap<>();
        m.put("instance", inst);
        m.put("schema", schema);
        return m;
    }

    private String uniqueToken() {
        String token = FormSupport.generateToken();
        while (instances.existsByToken(token)) token = FormSupport.generateToken();
        return token;
    }

    private static LocalDateTime toLocal(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static <T> T fail(HttpStatus status, String msg) {
        throw new ResponseStatusException(status, msg);
    }
}
