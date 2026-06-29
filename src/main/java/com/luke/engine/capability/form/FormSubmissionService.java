package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.document.DocumentService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
 */
@Service
public class FormSubmissionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    private static final String FORMS_CAPABILITY = "FORMS";

    private final FormInstanceRepository instances;
    private final FormSubmissionOutboxRepository outbox;
    private final DocumentService documents;

    public FormSubmissionService(FormInstanceRepository instances, FormSubmissionOutboxRepository outbox,
                                 DocumentService documents) {
        this.instances = instances;
        this.outbox = outbox;
        this.documents = documents;
    }

    /**
     * Mark an instance SUBMITTED (merging any final data) and enqueue its process
     * start. Atomic: the instance state change and the outbox row commit together.
     */
    @Transactional
    public void submit(FormInstance inst, Map<String, Object> dataToMerge) {
        submit(inst, dataToMerge, null);
    }

    /**
     * As {@link #submit(FormInstance, Map)}, but first binds attachments uploaded under
     * {@code attachmentSourceRef} (the embed flow's client-minted processRef, distinct from the new
     * instance id) to this instance — so the {@code formMetaData} attachment snapshot built at enqueue
     * sees them. Authenticated submits pass {@code null} (their docs already carry the instance id).
     */
    @Transactional
    public void submit(FormInstance inst, Map<String, Object> dataToMerge, String attachmentSourceRef) {
        if (dataToMerge != null) inst.setData(merge(inst.getData(), dataToMerge));
        inst.setState(FormInstanceStates.SUBMITTED);
        if (inst.getSubmittedAt() == null) inst.setSubmittedAt(LocalDateTime.now());
        markQueued(inst);
        instances.save(inst);          // assigns the id for a new (embed) instance
        if (StringUtils.hasText(attachmentSourceRef) && !attachmentSourceRef.equals(inst.getId())) {
            // Best-effort: a registry hiccup must never block the submission itself.
            try {
                documents.linkToInstance(inst.getTenantId(), attachmentSourceRef, inst.getId());
            } catch (RuntimeException ignored) { /* snapshot just omits them */ }
        }
        enqueue(inst);
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
}
