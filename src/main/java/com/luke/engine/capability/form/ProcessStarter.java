package com.luke.engine.capability.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Starts the generic intake process in core-engine after a form submission, and
 * records the OUTCOME on the instance so the UI tracker can show exactly what
 * happened — started (with the process id) or failed (with the error). The call
 * is the first cap→core hop, authenticated with a shared secret. BEST-EFFORT: a
 * failure never blocks the submission; it's captured, not thrown.
 */
@Component
public class ProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(ProcessStarter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = new RestTemplate();

    @Value("${luke.core-engine.base-url:http://localhost:8080}")
    private String coreBaseUrl;

    @Value("${luke.internal.shared-secret:}")
    private String sharedSecret;

    /** Outcome of a start attempt. status = STARTED | FAILED. */
    public record StartResult(String processInstanceId, String status, String error) {}

    /**
     * Start the intake process for a submitted instance AND record the outcome on
     * the instance's context (processStartStatus / processInstanceId /
     * processStartError / processStartAt). The caller persists the instance.
     */
    public StartResult startForInstance(FormInstance inst) {
        // Two process variables only: formData (the answers) and formMetaData
        // (everything else), both as JSON. core-engine stores them as JSON (Spin)
        // variables, navigable as ${formData.prop('email')} / ${formMetaData.prop('instanceId')}.
        Map<String, Object> vars = new HashMap<>();
        try {
            vars.put("formData", MAPPER.writeValueAsString(inst.getData() != null ? inst.getData() : Map.of()));
        } catch (Exception e) {
            vars.put("formData", "{}");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("instanceId", inst.getId());
        meta.put("formCode", inst.getDefinitionCode());
        meta.put("tenantId", inst.getTenantId());
        meta.put("version", inst.getVersion());
        try {
            vars.put("formMetaData", MAPPER.writeValueAsString(meta));
        } catch (Exception e) {
            vars.put("formMetaData", "{}");
        }

        StartResult res = start(inst.getTenantId(), inst.getId(), vars);

        Map<String, Object> ctx = new HashMap<>(inst.getContext() != null ? inst.getContext() : Map.of());
        ctx.put("processStartStatus", res.status());
        ctx.put("processStartAt", System.currentTimeMillis());
        if (res.processInstanceId() != null) ctx.put("processInstanceId", res.processInstanceId());
        if (res.error() != null) ctx.put("processStartError", res.error()); else ctx.remove("processStartError");
        inst.setContext(ctx);
        return res;
    }

    private StartResult start(String tenantId, String businessKey, Map<String, Object> variables) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (sharedSecret != null && !sharedSecret.isBlank()) headers.set("X-Internal-Key", sharedSecret);

            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId);
            body.put("businessKey", businessKey);
            body.put("variables", variables);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.postForObject(
                    coreBaseUrl + "/api/internal/process-start", new HttpEntity<>(body, headers), Map.class);
            Object pid = resp != null ? resp.get("processInstanceId") : null;
            if (pid != null) return new StartResult(pid.toString(), "STARTED", null);
            return new StartResult(null, "FAILED", "core-engine returned no process instance id");
        } catch (HttpStatusCodeException e) {
            String detail = messageFrom(e.getResponseBodyAsString(), e.getStatusText());
            log.warn("Intake process start failed for tenant {} (instance {}): HTTP {} — {}", tenantId, businessKey, e.getStatusCode().value(), detail);
            return new StartResult(null, "FAILED", "HTTP " + e.getStatusCode().value() + " — " + detail);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Intake process start failed for tenant {} (instance {}): {}", tenantId, businessKey, detail);
            return new StartResult(null, "FAILED", detail);
        }
    }

    /** Pull a human message out of core-engine's JSON error body, else trim the body. */
    private static String messageFrom(String body, String fallback) {
        if (body == null || body.isBlank()) return fallback;
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.hasNonNull("message")) return node.get("message").asText();
            if (node.hasNonNull("error")) return node.get("error").asText();
        } catch (Exception ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
