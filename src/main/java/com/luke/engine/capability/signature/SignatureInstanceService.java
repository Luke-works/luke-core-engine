package com.luke.engine.capability.signature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runtime lifecycle for SIGNATURE INSTANCES (the campaign → contract → closure flow). Starting a
 * campaign binds a published {@link SignatureDefinition} to its recipients (one per signer role) and
 * the supplied {@code {{key}}} data-attribute values, mints per-recipient signing tokens, writes a
 * transactional {@link SignatureProcessOutbox} row (the Camunda hand-off, consumed by core at
 * merge), and tracks state to closure as recipients sign. PAdES sealing of the finished document is
 * a downstream step (reuses the SIG-2 engine) — here we capture each signature + drive the status.
 */
@Service
public class SignatureInstanceService {

    private final SignatureInstanceRepository instances;
    private final SignatureRecipientRepository recipients;
    private final SignatureProcessOutboxRepository outbox;
    private final SignatureDefinitionRepository defs;
    private final SignatureVersionRepository versions;
    private final DocumentStore documentStore;
    private final SignatureProvider signatureProvider;
    private final ObjectMapper mapper;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public SignatureInstanceService(SignatureInstanceRepository instances,
                                    SignatureRecipientRepository recipients,
                                    SignatureProcessOutboxRepository outbox,
                                    SignatureDefinitionRepository defs,
                                    SignatureVersionRepository versions,
                                    DocumentStore documentStore,
                                    SignatureProvider signatureProvider,
                                    ObjectMapper mapper) {
        this.instances = instances;
        this.recipients = recipients;
        this.outbox = outbox;
        this.defs = defs;
        this.versions = versions;
        this.documentStore = documentStore;
        this.signatureProvider = signatureProvider;
        this.mapper = mapper;
    }

    // ── inputs / outputs ─────────────────────────────────────────────────────────────
    public record RecipientInput(String signerId, String name, String email) {}
    public record CampaignInput(String definitionCode, Integer version, String name,
                                List<RecipientInput> recipients, Map<String, String> values, Integer expiresInDays) {}

    public record FieldView(String id, String type, int page, double x, double y, double w, double h) {}
    public record RecipientSession(String documentName, String signerName, String pdfBase64,
                                   List<FieldView> fields, Map<String, String> values, String state) {}

    // ── campaign start ────────────────────────────────────────────────────────────────
    @Transactional
    public SignatureInstance startCampaign(String tenantId, String userId, CampaignInput in) {
        if (in == null || in.definitionCode() == null || in.definitionCode().isBlank()) {
            throw bad("definitionCode is required");
        }
        if (in.recipients() == null || in.recipients().isEmpty()) {
            throw bad("at least one recipient is required");
        }
        SignatureDefinition def = defs.findByTenantIdAndCode(tenantId, in.definitionCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown definition: " + in.definitionCode()));
        if (def.getPublishedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Definition has no published version");
        }
        int ver = in.version() != null ? in.version() : def.getPublishedVersion();
        SignatureVersion sv = versions.findByDefinitionIdAndVersion(def.getId(), ver)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown version v" + ver));

        SignatureSchemaModel.Parsed schema = SignatureSchemaModel.parse(mapper, sv.getSchema());
        if (schema.signers().isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Definition has no signers");

        // Index supplied recipients by role; every role must be filled.
        Map<String, RecipientInput> byRole = new LinkedHashMap<>();
        for (RecipientInput r : in.recipients()) {
            if (r == null || r.signerId() == null) throw bad("each recipient needs a signerId");
            if (isBlank(r.name()) || isBlank(r.email())) throw bad("each recipient needs a name and email");
            byRole.put(r.signerId(), r);
        }
        for (SignatureSchemaModel.Signer role : schema.signers()) {
            if (!byRole.containsKey(role.id())) throw bad("missing recipient for signer \"" + role.label() + "\"");
        }
        // Required variables must be supplied.
        Map<String, String> values = in.values() == null ? Map.of() : in.values();
        for (SignatureSchemaModel.Variable v : schema.variables()) {
            if (v.required() && isBlank(values.get(v.key()))) throw bad("missing value for {{" + v.key() + "}}");
        }

        boolean sequential = !"parallel".equalsIgnoreCase(schema.routing());
        LocalDateTime now = LocalDateTime.now();

        SignatureInstance inst = new SignatureInstance();
        inst.setTenantId(tenantId);
        inst.setToken(SignatureSupport.generateSignToken());
        inst.setDefinitionCode(def.getCode());
        inst.setDefinitionVersion(ver);
        inst.setName(isBlank(in.name()) ? def.getName() : in.name().trim());
        inst.setState(SignatureInstanceStates.CREATED);
        inst.setValuesJson(writeValues(values));
        inst.setBusinessKey("SC-" + inst.getToken().substring(0, 12));
        inst.setCreatedBy(userId);
        if (in.expiresInDays() != null && in.expiresInDays() > 0) inst.setExpiresAt(now.plusDays(in.expiresInDays()));
        inst = instances.save(inst);

        // One recipient per signer role; sequential routing arms only the first.
        List<SignatureSchemaModel.Signer> roles = schema.signers().stream()
                .sorted(Comparator.comparingInt(SignatureSchemaModel.Signer::order)).toList();
        for (int i = 0; i < roles.size(); i++) {
            SignatureSchemaModel.Signer role = roles.get(i);
            RecipientInput ri = byRole.get(role.id());
            SignatureRecipient r = new SignatureRecipient();
            r.setInstanceId(inst.getId());
            r.setTenantId(tenantId);
            r.setSignerId(role.id());
            r.setName(ri.name().trim());
            r.setEmail(ri.email().trim());
            r.setSigningOrder(role.order());
            r.setVerify(role.verify());
            r.setSignToken(SignatureSupport.generateSignToken());
            r.setState((!sequential || i == 0) ? SignatureInstanceStates.R_SENT : SignatureInstanceStates.R_PENDING);
            recipients.save(r);
        }

        // Transactional outbox row → core starts/correlates the Camunda process at merge.
        outbox.save(new SignatureProcessOutbox(inst.getBusinessKey(), inst.getId(), tenantId, def.getCode()));

        // Links delivered.
        inst.setState(SignatureInstanceStates.SENT);
        return instances.save(inst);
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────
    public SignatureInstance get(String tenantId, String id) {
        return instances.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown signature instance: " + id));
    }

    public List<SignatureInstance> list(String tenantId, String definitionCode) {
        return (definitionCode == null || definitionCode.isBlank())
                ? instances.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : instances.findByTenantIdAndDefinitionCodeOrderByCreatedAtDesc(tenantId, definitionCode);
    }

    public List<SignatureRecipient> recipientsOf(String tenantId, String id) {
        get(tenantId, id);
        return recipients.findByInstanceIdOrderBySigningOrderAsc(id);
    }

    @Transactional
    public SignatureInstance cancel(String tenantId, String id, String userId) {
        SignatureInstance inst = get(tenantId, id);
        if (SignatureInstanceStates.isTerminal(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instance is already " + inst.getState().toLowerCase());
        }
        inst.setState(SignatureInstanceStates.CANCELLED);
        return instances.save(inst);
    }

    // ── public per-recipient signing (token-authenticated; NO tenant header) ─────────────
    @Transactional
    public RecipientSession viewForSigning(String token) {
        SignatureRecipient r = recipients.findBySignToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or invalid signing link"));
        SignatureInstance inst = instances.findById(r.getInstanceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or invalid signing link"));
        guardSignable(inst, r);

        SignatureSchemaModel.Parsed schema = loadSchema(inst);
        if (schema.documentKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Definition has no document");
        }
        byte[] pdf = documentStore.get(inst.getTenantId(), schema.documentKey());
        List<FieldView> mine = schema.fields().stream()
                .filter(f -> r.getSignerId().equals(f.signerId()))
                .map(f -> new FieldView(f.id(), f.type(), f.page(), f.x(), f.y(), f.w(), f.h()))
                .toList();

        // Mark opened (recipient + instance) on first view.
        if (SignatureInstanceStates.R_SENT.equals(r.getState())) {
            r.setState(SignatureInstanceStates.R_OPENED);
            recipients.save(r);
        }
        if (SignatureInstanceStates.SENT.equals(inst.getState())) {
            inst.setState(SignatureInstanceStates.OPENED);
            instances.save(inst);
        }
        return new RecipientSession(schema.documentName(), r.getName(),
                Base64.getEncoder().encodeToString(pdf), mine, readValues(inst.getValuesJson()), inst.getState());
    }

    @Transactional
    public void sign(String token, byte[] signaturePng, boolean consent) {
        if (!consent) throw bad("Consent is required to sign");
        SignatureRecipient r = recipients.findBySignToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or invalid signing link"));
        SignatureInstance inst = instances.findById(r.getInstanceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or invalid signing link"));
        guardSignable(inst, r);

        // Capture the signature PNG in the DocumentStore (never in the DB). Sealing into the final
        // PAdES document is a downstream step that reuses the SIG-2 signing engine.
        String key = UUID.randomUUID().toString().replace("-", "") + ".png";
        documentStore.put(inst.getTenantId(), key, signaturePng, "image/png");
        LocalDateTime now = LocalDateTime.now();
        r.setSignatureObjectKey(key);
        r.setState(SignatureInstanceStates.R_SIGNED);
        r.setSignedAt(now);
        recipients.save(r);

        List<SignatureRecipient> all = recipients.findByInstanceIdOrderBySigningOrderAsc(inst.getId());
        boolean allSigned = all.stream().allMatch(x -> SignatureInstanceStates.R_SIGNED.equals(x.getState()));
        if (allSigned) {
            inst.setState(SignatureInstanceStates.COMPLETED);
            inst.setCompletedAt(now);
            // processStatus (the Camunda binding) is owned by SignatureProcessOutboxConsumer:
            // STARTED when the ceremony launches, CLOSED once this closure is correlated.
            // Closure: seal the finished document. A seal failure does NOT roll back the recorded
            // signatures or the COMPLETED state — it is captured as FAILED and retryable via reseal.
            sealClosure(inst, all);
        } else {
            inst.setState(SignatureInstanceStates.IN_PROGRESS);
            // Sequential routing: arm the next pending recipient.
            all.stream()
                    .filter(x -> SignatureInstanceStates.R_PENDING.equals(x.getState()))
                    .min(Comparator.comparingInt(SignatureRecipient::getSigningOrder))
                    .ifPresent(next -> {
                        next.setState(SignatureInstanceStates.R_SENT);
                        recipients.save(next);
                    });
        }
        instances.save(inst);
    }

    // ── sealed document (closure) ────────────────────────────────────────────────────────
    /** Stream the final sealed PDF. 409 until the instance is COMPLETED and sealed. */
    public byte[] signedPdf(String tenantId, String id) {
        SignatureInstance inst = get(tenantId, id);
        if (!"SEALED".equals(inst.getSealStatus()) || inst.getSignedObjectKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document is not sealed yet");
        }
        return documentStore.get(tenantId, inst.getSignedObjectKey());
    }

    /** Retry sealing a completed instance whose closure seal failed (or to re-stamp). */
    @Transactional
    public SignatureInstance reseal(String tenantId, String id, String userId) {
        SignatureInstance inst = get(tenantId, id);
        if (!SignatureInstanceStates.COMPLETED.equals(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instance is not completed");
        }
        sealClosure(inst, recipients.findByInstanceIdOrderBySigningOrderAsc(id));
        return instances.save(inst);
    }

    /**
     * Build the final document from the pinned schema: stamp each recipient's signature PNG
     * (SIGNATURE/INITIALS) or rendered text (DATE/NAME) at its field, append a multi-signer
     * certificate, PAdES-seal, and store. Sets sealStatus SEALED|FAILED (never throws to the caller).
     */
    private void sealClosure(SignatureInstance inst, List<SignatureRecipient> all) {
        try {
            SignatureSchemaModel.Parsed schema = loadSchema(inst);
            if (schema.documentKey() == null) throw new IllegalStateException("definition has no document");
            byte[] source = documentStore.get(inst.getTenantId(), schema.documentKey());

            Map<String, SignatureRecipient> byRole = new LinkedHashMap<>();
            for (SignatureRecipient r : all) byRole.putIfAbsent(r.getSignerId(), r);

            List<EnvelopeSealRequest.FieldStamp> stamps = new ArrayList<>();
            for (SignatureSchemaModel.Field f : schema.fields()) {
                SignatureRecipient r = byRole.get(f.signerId());
                if (r == null) continue;
                Field ef = new Field(f.page(), f.x(), f.y(), f.w(), f.h());
                switch (f.type()) {
                    case "SIGNATURE", "INITIALS" -> {
                        if (r.getSignatureObjectKey() != null) {
                            byte[] png = documentStore.get(inst.getTenantId(), r.getSignatureObjectKey());
                            stamps.add(EnvelopeSealRequest.FieldStamp.image(ef, png));
                        }
                    }
                    case "DATE" -> stamps.add(EnvelopeSealRequest.FieldStamp.text(ef, r.getSignedAt() != null ? DATE.format(r.getSignedAt()) : ""));
                    case "NAME" -> stamps.add(EnvelopeSealRequest.FieldStamp.text(ef, r.getName()));
                    default -> { /* TEXT merge deferred */ }
                }
            }
            List<EnvelopeSealRequest.SignerSummary> signers = all.stream()
                    .map(r -> new EnvelopeSealRequest.SignerSummary(r.getName(), r.getEmail(),
                            r.getSignedAt() != null ? DATE.format(r.getSignedAt()) : ""))
                    .toList();
            byte[] sealed = signatureProvider.sealEnvelope(new EnvelopeSealRequest(source, stamps,
                    new EnvelopeSealRequest.EnvelopeMeta(inst.getName(), SignatureSupport.sha256Hex(source), signers)));

            String key = UUID.randomUUID().toString().replace("-", "") + ".pdf";
            documentStore.put(inst.getTenantId(), key, sealed, "application/pdf");
            inst.setSignedObjectKey(key);
            inst.setSignedSha256(SignatureSupport.sha256Hex(sealed));
            inst.setSealStatus("SEALED");
            inst.setSealError(null);
        } catch (Exception e) {
            inst.setSealStatus("FAILED");
            inst.setSealError(e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────
    private void guardSignable(SignatureInstance inst, SignatureRecipient r) {
        if (SignatureInstanceStates.isTerminal(inst.getState())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This contract is " + inst.getState().toLowerCase());
        }
        if (SignatureInstanceStates.R_SIGNED.equals(r.getState())) {
            throw new ResponseStatusException(HttpStatus.GONE, "You have already signed");
        }
        if (SignatureInstanceStates.R_PENDING.equals(r.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "It is not your turn to sign yet");
        }
    }

    private SignatureSchemaModel.Parsed loadSchema(SignatureInstance inst) {
        SignatureDefinition def = defs.findByTenantIdAndCode(inst.getTenantId(), inst.getDefinitionCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Definition no longer exists"));
        SignatureVersion sv = versions.findByDefinitionIdAndVersion(def.getId(), inst.getDefinitionVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pinned version no longer exists"));
        return SignatureSchemaModel.parse(mapper, sv.getSchema());
    }

    private String writeValues(Map<String, String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> readValues(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** Optional accessor used by the (future) core outbox consumer / tests. */
    public Optional<SignatureProcessOutbox> outboxFor(String businessKey) {
        return outbox.findByStateOrderByCreatedAtAsc(SignatureProcessOutbox.QUEUED).stream()
                .filter(o -> businessKey.equals(o.getBusinessKey())).findFirst();
    }
}
