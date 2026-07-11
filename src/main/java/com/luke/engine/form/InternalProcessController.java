package com.luke.engine.form;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal, server-to-server endpoint to start a process after a form submission.
 * RETAINED behind the shared secret ({@code X-Internal-Key}) for any external/BPMN
 * caller (e.g. an HTTP connector). The in-process form-submission path now calls
 * {@link InternalProcessService} directly (no HTTP hop) — both share one impl.
 *
 * <p>Kept fail-closed: an unset/empty shared secret rejects every call (guards #41).
 * (Removal is M5-only, after the BPMN audit confirms no HTTP caller.)
 */
@RestController
@RequestMapping("/api/internal")
public class InternalProcessController {

    private static final Logger log = LoggerFactory.getLogger(InternalProcessController.class);

    private final InternalProcessService processService;
    private final IdentityService identityService;

    @Value("${luke.internal.shared-secret:}")
    private String sharedSecret;

    public InternalProcessController(InternalProcessService processService, IdentityService identityService) {
        this.processService = processService;
        this.identityService = identityService;
    }

    @PostConstruct
    void warnIfDisabled() {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            log.warn("InternalProcessController: luke.internal.shared-secret is UNSET — /api/internal/process-start "
                    + "will reject every call (form-intake process starts via HTTP are disabled). Set "
                    + "LUKE_INTERNAL_SHARED_SECRET if any external/BPMN caller uses this endpoint.");
        }
    }

    public record StartBody(String tenantId, String businessKey, Map<String, Object> variables) {}

    @PostMapping("/process-start")
    public Map<String, Object> start(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                     @RequestBody StartBody body) {
        // Fail closed + CONSTANT-TIME secret comparison (no length/prefix timing leak).
        if (sharedSecret == null || sharedSecret.isBlank() || !constantTimeEquals(key, sharedSecret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (body == null || body.tenantId() == null || body.tenantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        // Validate the tenant exists before starting a process scoped to it — the body
        // names the tenant outright, so an unknown/typo'd id must not silently create
        // an orphaned process in a non-existent tenant.
        if (identityService.createTenantQuery().tenantId(body.tenantId()).count() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tenant: " + body.tenantId());
        }
        try {
            String pid = processService.start(body.tenantId(), body.businessKey(), body.variables());
            return Map.of("processInstanceId", pid);
        } catch (Exception e) {
            log.warn("Failed to start intake for tenant {}: {}", body.tenantId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start process: " + e.getMessage());
        }
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null) return false;
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
