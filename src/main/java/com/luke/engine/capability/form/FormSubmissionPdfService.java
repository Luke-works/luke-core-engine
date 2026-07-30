package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.document.Document;
import com.luke.engine.document.DocumentRegistration;
import com.luke.engine.document.DocumentService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * When a form opts into "Save submission as Attachment" ({@code settings.saveSubmissionAsPdf}), this
 * renders the completed submission to a pixel-faithful PDF (via luke-file-proxy's headless Chromium) and
 * registers it as a READY FORMS {@link Document} on the form instance. The submission outbox calls this
 * just before mirroring the instance's documents into Camunda, so the PDF rides the existing mirror onto
 * the process instance and shows up in the Form Inbox + Core UI Tasklist alongside any uploaded files.
 *
 * <p>Strictly best-effort: any failure (renderer down, browser error, registry hiccup) is logged and
 * swallowed — it must NEVER fail or roll back the submission/process start.
 */
@Service
public class FormSubmissionPdfService {

    private static final Logger log = LoggerFactory.getLogger(FormSubmissionPdfService.class);
    private static final String FORMS_CAPABILITY = "FORMS";

    private final FormInstanceRepository instances;
    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;
    private final DocumentService documents;
    private final FormPdfRenderClient renderClient;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public FormSubmissionPdfService(FormInstanceRepository instances,
                                    FormDefinitionRepository forms,
                                    FormVersionRepository versions,
                                    DocumentService documents,
                                    FormPdfRenderClient renderClient,
                                    ObjectMapper mapper,
                                    @Value("${luke.forms.submission-pdf-enabled:true}") boolean enabled) {
        this.instances = instances;
        this.forms = forms;
        this.versions = versions;
        this.documents = documents;
        this.renderClient = renderClient;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    /** Generate + register the submission PDF for an instance IF its form opted in. Best-effort. */
    public void maybeGenerate(String tenantId, String instanceId, String processInstanceId, String processBusinessKey) {
        if (!enabled || !renderClient.isConfigured()) return;
        try {
            FormInstance inst = instances.findById(instanceId).orElse(null);
            if (inst == null) return;
            String schema = schemaFor(inst);
            if (schema == null || !saveSubmissionAsPdf(schema)) return; // form did not opt in

            String processRef = (processBusinessKey != null && !processBusinessKey.isBlank()) ? processBusinessKey : instanceId;
            String code = inst.getDefinitionCode() != null ? inst.getDefinitionCode() : "form";
            String filename = code + "-submission.pdf";
            // TENANT-RELATIVE key; file-proxy prepends {tenantId}/. Idempotent on (tenant, key) so a
            // reprocessed outbox row re-renders into the same object + updates the same Document row.
            String storageKey = processRef + "/" + instanceId + "-submission.pdf";
            Map<String, Object> data = inst.getData() != null ? inst.getData() : Map.of();

            // The provenance block makes the PDF self-contained evidence: it records WHEN the form was
            // submitted, from which IP and device, and through which door — the same values captured on
            // the instance and in formMetaData, so the three can be reconciled.
            Map<String, Object> provenance = new java.util.LinkedHashMap<>();
            provenance.put("instanceId", instanceId);
            provenance.put("formCode", inst.getDefinitionCode());
            provenance.put("version", inst.getVersion());
            provenance.put("submittedAt", inst.getSubmittedAt() != null ? inst.getSubmittedAt().toString() : null);
            provenance.put("ip", inst.getSubmittedIp());
            provenance.put("userAgent", inst.getSubmittedUserAgent());
            provenance.put("via", inst.getSubmittedVia());

            FormPdfRenderClient.Result r = renderClient.render(tenantId, storageKey, schema, data, null, provenance);

            documents.registerStored(new DocumentRegistration(
                    tenantId, processRef, processInstanceId, null,
                    Document.KIND_FORM_SUBMISSION_PDF, FORMS_CAPABILITY, instanceId,
                    storageKey, filename, "application/pdf",
                    r != null ? r.sizeBytes() : null, r != null ? r.sha256() : null,
                    null, "system", "Lukeflow"));
            log.info("Submission PDF stored for instance {} (tenant {}) → {}", instanceId, tenantId, storageKey);
        } catch (RuntimeException e) {
            log.warn("Submission PDF generation failed for instance {} (tenant {}): {}",
                    instanceId, tenantId, e.getMessage());
        }
    }

    private String schemaFor(FormInstance inst) {
        return forms.findByTenantIdAndCode(inst.getTenantId(), inst.getDefinitionCode())
                .flatMap(f -> versions.findByFormIdAndVersion(f.getId(), inst.getVersion()))
                .map(FormVersion::getSchema)
                .orElse(null);
    }

    /** Server-side mirror of the renderer's {@code readSaveSubmissionAsPdf} — tolerant of bad JSON. */
    private boolean saveSubmissionAsPdf(String schemaJson) {
        try {
            JsonNode node = mapper.readTree(schemaJson).path("settings").path("saveSubmissionAsPdf");
            return node.isBoolean() && node.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
