package com.luke.engine.form;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
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
 * Internal, server-to-server endpoint for capability-engine to start a process
 * after a form submission. NOT public — the gateway only exposes /api/public/**,
 * so this is reachable only on the internal network. Guarded by a shared secret
 * ({@code X-Internal-Key}), mirroring capability-engine's OperatorAuthFilter in
 * the reverse direction.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalProcessController {

    private static final Logger log = LoggerFactory.getLogger(InternalProcessController.class);

    private final RuntimeService runtimeService;
    private final IdentityService identityService;

    @Value("${luke.forms.intake-process-key:FormSubmissionIntakeProcess}")
    private String intakeProcessKey;

    @Value("${luke.internal.shared-secret:}")
    private String sharedSecret;

    public InternalProcessController(RuntimeService runtimeService, IdentityService identityService) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    public record StartBody(String tenantId, String businessKey, Map<String, Object> variables) {}

    @PostMapping("/process-start")
    public Map<String, Object> start(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                     @RequestBody StartBody body) {
        if (sharedSecret == null || sharedSecret.isBlank() || !sharedSecret.equals(key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (body == null || body.tenantId() == null || body.tenantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId is required");
        }

        Map<String, Object> vars = body.variables() != null ? new HashMap<>(body.variables()) : new HashMap<>();

        // Scope the engine to the tenant so the tenant-specific definition is
        // selected and the instance is tagged with the tenant.
        identityService.setAuthentication(null, null, List.of(body.tenantId()));
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceByKey(intakeProcessKey)
                    .processDefinitionTenantId(body.tenantId())
                    .businessKey(body.businessKey())
                    .setVariables(vars)
                    .execute();
            log.info("Started {} (tenant {}, businessKey {}) → {}", intakeProcessKey, body.tenantId(), body.businessKey(), pi.getProcessInstanceId());
            return Map.of(
                    "processInstanceId", pi.getProcessInstanceId(),
                    "definitionId", pi.getProcessDefinitionId());
        } catch (Exception e) {
            // Surface as a 502 so the caller can log it; the caller treats it best-effort.
            log.warn("Failed to start {} for tenant {}: {}", intakeProcessKey, body.tenantId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start process: " + e.getMessage());
        } finally {
            identityService.clearAuthentication();
        }
    }
}
