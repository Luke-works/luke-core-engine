package com.luke.engine.capability.signature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.capability.signature.SignatureInstanceService.CampaignInput;
import com.luke.engine.tenant.UserDirectory;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated, tenant-scoped runtime API for SIGNATURE INSTANCES (campaigns)
 * ({@code /api/signature-instances}). Start a campaign from a published definition, list/track
 * instances, and cancel. The per-recipient signing links (tokens) are returned to the authed
 * sender so they can deliver them; the public signing surface is {@link PublicInstanceSignController}.
 */
@RestController
@RequestMapping("/api/signature-instances")
public class SignatureInstanceController {

    private final SignatureInstanceService service;
    private final UserDirectory userDirectory;
    private final ObjectMapper mapper;

    public SignatureInstanceController(SignatureInstanceService service, UserDirectory userDirectory, ObjectMapper mapper) {
        this.service = service;
        this.userDirectory = userDirectory;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstanceDetail start(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @RequestBody CampaignBody body) {
        requireTenant(tenantId);
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaign body is required");
        SignatureInstance inst = service.startCampaign(tenantId, userId, body.toInput());
        return detail(inst);
    }

    @GetMapping
    public List<InstanceView> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestParam(value = "definitionCode", required = false) String definitionCode) {
        requireTenant(tenantId);
        return service.list(tenantId, definitionCode).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public InstanceDetail get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return detail(service.get(tenantId, id));
    }

    @PostMapping("/{id}/cancel")
    public InstanceView cancel(@RequestHeader("X-Tenant-Id") String tenantId,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @PathVariable String id) {
        requireTenant(tenantId);
        return view(service.cancel(tenantId, id, userId));
    }

    @GetMapping("/{id}/signed.pdf")
    public ResponseEntity<byte[]> signedPdf(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        byte[] bytes = service.signedPdf(tenantId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"signed.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }

    @PostMapping("/{id}/seal")
    public InstanceView seal(@RequestHeader("X-Tenant-Id") String tenantId,
                             @RequestHeader(value = "X-User-Id", required = false) String userId,
                             @PathVariable String id) {
        requireTenant(tenantId);
        return view(service.reseal(tenantId, id, userId));
    }

    @PostMapping("/{id}/recipients/{signerId}/remind")
    public void remind(@RequestHeader("X-Tenant-Id") String tenantId,
                       @PathVariable String id, @PathVariable String signerId) {
        requireTenant(tenantId);
        boolean known = service.recipientsOf(tenantId, id).stream().anyMatch(r -> r.getSignerId().equals(signerId));
        if (!known) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown recipient");
        // Email delivery lands with SIG-9 (Postmark); the link is already available to the sender.
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────
    private InstanceDetail detail(SignatureInstance inst) {
        List<RecipientView> rcpts = service.recipientsOf(inst.getTenantId(), inst.getId()).stream()
                .map(RecipientView::from).toList();
        return new InstanceDetail(view(inst), rcpts);
    }

    private InstanceView view(SignatureInstance inst) {
        return InstanceView.from(inst, readValues(inst.getValuesJson()), userDirectory.nameFor(inst.getCreatedBy()));
    }

    private Map<String, String> readValues(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────────────
    public record RecipientBody(String signerId, String name, String email) {}
    public record CampaignBody(String definitionCode, Integer version, String name,
                               List<RecipientBody> recipients, Map<String, String> values, Integer expiresInDays) {
        CampaignInput toInput() {
            List<SignatureInstanceService.RecipientInput> rs = recipients == null ? List.of()
                    : recipients.stream().map(r -> new SignatureInstanceService.RecipientInput(r.signerId(), r.name(), r.email())).toList();
            return new CampaignInput(definitionCode, version, name, rs, values, expiresInDays);
        }
    }

    public record InstanceView(String id, String token, String name, String definitionCode, int definitionVersion,
                               String state, String sealStatus, Map<String, String> values, String businessKey,
                               String processInstanceId, String createdBy, String createdByName,
                               Long createdAt, Long completedAt, Long expiresAt) {
        static InstanceView from(SignatureInstance i, Map<String, String> values, String createdByName) {
            return new InstanceView(i.getId(), i.getToken(), i.getName(), i.getDefinitionCode(), i.getDefinitionVersion(),
                    i.getState(), i.getSealStatus(), values, i.getBusinessKey(), i.getProcessInstanceId(),
                    i.getCreatedBy(), createdByName,
                    SignatureSupport.epochMillis(i.getCreatedAt()), SignatureSupport.epochMillis(i.getCompletedAt()),
                    SignatureSupport.epochMillis(i.getExpiresAt()));
        }
    }

    /** Authed recipient view — includes the signing token so the sender can deliver the link. */
    public record RecipientView(String signerId, String name, String email, int order, String verify,
                                String state, String signToken, Long signedAt) {
        static RecipientView from(SignatureRecipient r) {
            return new RecipientView(r.getSignerId(), r.getName(), r.getEmail(), r.getSigningOrder(), r.getVerify(),
                    r.getState(), r.getSignToken(), SignatureSupport.epochMillis(r.getSignedAt()));
        }
    }

    public record InstanceDetail(InstanceView instance, List<RecipientView> recipients) {}
}
