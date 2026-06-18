package com.luke.engine.form;

import java.util.Map;
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

    @Value("${luke.internal.shared-secret:}")
    private String sharedSecret;

    public InternalProcessController(InternalProcessService processService) {
        this.processService = processService;
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
        try {
            String pid = processService.start(body.tenantId(), body.businessKey(), body.variables());
            return Map.of("processInstanceId", pid);
        } catch (Exception e) {
            log.warn("Failed to start intake for tenant {}: {}", body.tenantId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start process: " + e.getMessage());
        }
    }
}
