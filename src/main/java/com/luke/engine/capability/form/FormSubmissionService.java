package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.document.DocumentService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Durable submit→process bridge. Marks a form instance SUBMITTED and enqueues its
 * Camunda process start in ONE transaction (the form instance row + the outbox row
 * commit together), so a crash between the two can never lose the start intent.
 * {@link FormSubmissionOutboxConsumer} actually starts the process, off-thread.
 *
 * <p>Replaces the old synchronous, best-effort cap→core HTTP {@code ProcessStarter}.
 *
 * <p>This is also the SINGLE CHOKE POINT for server-side submission validation: every door
 * (public embed, the OTP recipient portal, and the authenticated in-app fill) funnels through
 * {@link #submit}, so {@link SubmissionValidator} runs here rather than in each controller —
 * a future submit path cannot forget it.
 */
@Service
public class FormSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(FormSubmissionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    private static final String FORMS_CAPABILITY = "FORMS";

    private final FormInstanceRepository instances;
    private final FormSubmissionOutboxRepository outbox;
    private final DocumentService documents;
    private final FormEventPublisher events;
    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;

    public FormSubmissionService(FormInstanceRepository instances, FormSubmissionOutboxRepository outbox,
                                 DocumentService documents, FormEventPublisher events,
                                 FormDefinitionRepository forms, FormVersionRepository versions) {
        this.instances = instances;
        this.outbox = outbox;
        this.documents = documents;
        this.events = events;
        this.forms = forms;
        this.versions = versions;
    }

    /**
     * Mark an instance SUBMITTED (merging any final data) and enqueue its process
     * start. Atomic: the instance state change and the outbox row commit together.
     */
    @Transactional
    public void submit(FormInstance inst, Map<String, Object> dataToMerge) {
        submit(inst, dataToMerge, null, null);
    }

    /** Retained overload: attachments without provenance. */
    @Transactional
    public void submit(FormInstance inst, Map<String, Object> dataToMerge, String attachmentSourceRef) {
        submit(inst, dataToMerge, attachmentSourceRef, null);
    }

    /**
     * As {@link #submit(FormInstance, Map)}, but first binds attachments uploaded under
     * {@code attachmentSourceRef} (the embed flow's client-minted processRef, distinct from the new
     * instance id) to this instance — so the {@code formMetaData} attachment snapshot built at enqueue
     * sees them. Authenticated submits pass {@code null} (their docs already carry the instance id).
     */
    @Transactional
    public void submit(FormInstance inst, Map<String, Object> dataToMerge, String attachmentSourceRef,
                       SubmissionSource source) {
        // Server-side backstop, for EVERY door. Validate the MERGED map (stored + incoming), not the
        // incoming delta: an outbound instance carries preparer-supplied values from an earlier save,
        // so a delta alone would look like a submission with required fields missing.
        Map<String, Object> merged = merge(inst.getData(), dataToMerge);
        Map<String, Object> cleaned = SubmissionValidator.clean(schemaFor(inst), merged);
        logDroppedKeys(inst, merged, cleaned);
        inst.setData(cleaned);
        inst.setState(FormInstanceStates.SUBMITTED);
        if (inst.getSubmittedAt() == null) inst.setSubmittedAt(LocalDateTime.now());
        // Provenance is written HERE, the one choke point every door funnels through, so a submit path
        // added later inherits it and cannot silently skip it (the same reasoning as the validation
        // backstop above). Write-once: a re-submit/retry of an already-recorded instance must not
        // overwrite the original evidence.
        if (source != null && inst.getSubmittedIp() == null && inst.getSubmittedVia() == null) {
            inst.setSubmittedIp(source.ip());
            inst.setSubmittedUserAgent(source.userAgent());
            inst.setSubmittedVia(source.via());
        }
        markQueued(inst);
        instances.save(inst);          // assigns the id for a new (embed) instance
        if (StringUtils.hasText(attachmentSourceRef) && !attachmentSourceRef.equals(inst.getId())) {
            // Best-effort: a registry hiccup must never block the submission itself.
            try {
                documents.linkToInstance(inst.getTenantId(), attachmentSourceRef, inst.getId());
            } catch (RuntimeException ignored) { /* snapshot just omits them */ }
        }
        enqueue(inst);
        // Emit the forms→workflow lifecycle event on the same transaction, so a
        // submission and its "form submitted" event commit together.
        events.emit(inst, "submitted");
    }

    /**
     * Re-enqueue a submitted instance whose start failed (the retry path). Resets the
     * existing outbox row to QUEUED rather than adding a second one (businessKey is
     * unique).
     */
    @Transactional
    public void reEnqueue(FormInstance inst) {
        markQueued(inst);
        instances.save(inst);
        FormSubmissionOutbox row = outbox.findByBusinessKey(inst.getId()).orElse(null);
        if (row == null) {
            enqueue(inst);
            return;
        }
        row.setStatus("QUEUED");
        row.setErrorMessage(null);
        row.setProcessInstanceId(null);
        row.setRetryCount(row.getRetryCount() + 1);
        outbox.save(row);
    }

    private void enqueue(FormInstance inst) {
        if (outbox.findByBusinessKey(inst.getId()).isPresent()) return; // idempotent
        FormSubmissionOutbox row = new FormSubmissionOutbox();
        row.setTenantId(inst.getTenantId());
        row.setBusinessKey(inst.getId());
        row.setFormInstanceId(inst.getId());
        row.setProcessBusinessKey(newBusinessKey());
        row.setFormDataJson(json(inst.getData()));
        row.setFormMetaJson(metaJson(inst));
        row.setStatus("QUEUED");
        outbox.save(row);
    }

    /** Record QUEUED on the instance context so the UI tracker reflects the pending start. */
    private void markQueued(FormInstance inst) {
        Map<String, Object> ctx = new HashMap<>(inst.getContext() != null ? inst.getContext() : Map.of());
        ctx.put("processStartStatus", "QUEUED");
        ctx.remove("processStartError");
        inst.setContext(ctx);
    }

    private String metaJson(FormInstance inst) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("instanceId", inst.getId());
        meta.put("formCode", inst.getDefinitionCode());
        meta.put("tenantId", inst.getTenantId());
        meta.put("version", inst.getVersion());
        // Audit snapshot of what was attached at submission: docId -> [filename, "sha256:"+hash, size].
        // Immutable once written (snapshot semantics); a later add/remove does not rewrite formMetaData.
        meta.put("attachments", documents.attachmentAudit(inst.getTenantId(), FORMS_CAPABILITY, inst.getId()));
        // Submission provenance travels WITH the submission into the process instance, so the evidence
        // survives even if the instance row is later purged by retention.
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("at", inst.getSubmittedAt() != null ? inst.getSubmittedAt().toString() : null);
        submitted.put("ip", inst.getSubmittedIp());
        submitted.put("userAgent", inst.getSubmittedUserAgent());
        submitted.put("via", inst.getSubmittedVia());
        meta.put("submittedBy", submitted);
        return json(meta);
    }

    /** Camunda process business key: {@code SM-<7 alnum>-YYYYMMMDD}, e.g. SM-A3K9X2M-2026JUN18. */
    private static String newBusinessKey() {
        StringBuilder sb = new StringBuilder("SM-");
        for (int i = 0; i < 7; i++) sb.append(ALNUM.charAt(RNG.nextInt(ALNUM.length())));
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMMdd", java.util.Locale.ENGLISH))
                .toUpperCase(java.util.Locale.ENGLISH);
        return sb.append('-').append(date).toString();
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o != null ? o : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> add) {
        Map<String, Object> out = new HashMap<>(base == null ? Map.of() : base);
        if (add != null) out.putAll(add);
        return out;
    }

    /**
     * The schema this instance was created against — the instance's PINNED version, never the
     * definition's current published one, so editing (or re-publishing) a form can't retroactively
     * invalidate a submission that's already in flight. Null when the version can't be resolved;
     * {@link SubmissionValidator} then falls back to cleaning-without-a-field-contract rather than
     * dropping the whole submission.
     */
    private String schemaFor(FormInstance inst) {
        return forms.findByTenantIdAndCode(inst.getTenantId(), inst.getDefinitionCode())
                .flatMap(f -> versions.findByFormIdAndVersion(f.getId(), inst.getVersion()))
                .map(FormVersion::getSchema)
                .orElse(null);
    }

    /**
     * Record which keys the backstop discarded. Cleaning is silent by design (a tampered payload
     * shouldn't get feedback), but an instance whose STORED data carries keys the schema no longer
     * declares is worth seeing — that's schema drift, not an attack, and this is how we'd notice.
     */
    private void logDroppedKeys(FormInstance inst, Map<String, Object> before, Map<String, Object> after) {
        if (before.size() == after.size()) return;
        List<String> dropped = before.keySet().stream().filter(k -> !after.containsKey(k)).sorted().toList();
        if (dropped.isEmpty()) return;
        log.info("Submission backstop dropped {} undeclared key(s) for instance {} (tenant {}, form {} v{}): {}",
                dropped.size(), inst.getId(), inst.getTenantId(), inst.getDefinitionCode(), inst.getVersion(), dropped);
    }
}
