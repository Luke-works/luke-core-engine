package com.luke.engine.capability.phone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Vapi server webhooks for the phone capability — the only unauthenticated path here, under
 * {@code /api/public/**} (public by design; see {@link com.luke.engine.config.ApiAuthFilter}). Every
 * request is authenticated by the {@code X-Vapi-Secret} shared secret via {@link VapiWebhookVerifier}
 * before anything is read, so an unsigned/forged call is rejected.
 *
 * <p>Handled message types ({@code message.type}):
 * <ul>
 *   <li><b>assistant-request</b> (sync) — route an inbound call: resolve the owning tenant + assistant
 *       from the dialed number, record the call, and return the {@code assistantId} to answer with.</li>
 *   <li><b>tool-calls</b> (sync) — dispatch each tool to {@link VapiToolHandler} and return a
 *       {@code results} entry per tool-call id within Vapi's ~7.5s window.</li>
 *   <li><b>status-update</b> (async) — advance the call's lifecycle.</li>
 *   <li><b>end-of-call-report</b> (async) — persist transcript/recording/summary/cost and mark terminal;
 *       the outbox consumer then correlates the call-ended message to finish the parked process.</li>
 * </ul>
 * Other message types are accepted and ignored. All responses are fast and idempotent (Vapi may retry).
 */
@RestController
@RequestMapping("/api/public/phone")
public class PublicPhoneWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PublicPhoneWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VapiWebhookVerifier verifier;
    private final PhoneCallService callService;
    private final VapiToolHandler toolHandler;

    public PublicPhoneWebhookController(VapiWebhookVerifier verifier, PhoneCallService callService,
                                        VapiToolHandler toolHandler) {
        this.verifier = verifier;
        this.callService = callService;
        this.toolHandler = toolHandler;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-Vapi-Secret", required = false) String secret,
            @RequestBody JsonNode body) {

        if (!verifier.verify(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid webhook secret"));
        }

        JsonNode message = body != null ? body.path("message") : null;
        if (message == null || message.isMissingNode()) {
            return ResponseEntity.ok(Map.of());
        }
        String type = text(message, "type");
        if (type == null) return ResponseEntity.ok(Map.of());

        try {
            return switch (type) {
                case "assistant-request" -> ResponseEntity.ok(handleAssistantRequest(message));
                case "tool-calls" -> ResponseEntity.ok(handleToolCalls(message));
                case "status-update" -> { handleStatusUpdate(message); yield ResponseEntity.ok(Map.of()); }
                case "end-of-call-report" -> { handleEndReport(message); yield ResponseEntity.ok(Map.of()); }
                default -> ResponseEntity.ok(Map.of());
            };
        } catch (Exception e) {
            // Never 500 a webhook on our own bug — log and acknowledge so Vapi doesn't hammer retries.
            log.warn("Vapi webhook '{}' handling errored: {}", type, e.getMessage());
            return ResponseEntity.ok(Map.of());
        }
    }

    /* ── handlers ───────────────────────────────────────────────── */

    private Map<String, Object> handleAssistantRequest(JsonNode message) {
        String vapiNumberId = text(message.path("phoneNumber"), "id");
        String vapiCallId = text(message.path("call"), "id");
        String customerNumber = text(message.path("customer"), "number");

        Optional<PhoneCallService.InboundRouting> routing = callService.resolveInboundRouting(vapiNumberId);
        if (routing.isEmpty() || isBlank(routing.get().assistantId())) {
            log.warn("Inbound call on unknown/unconfigured Vapi number {} — declining to route", vapiNumberId);
            return Map.of("error", "No assistant is configured for this number");
        }
        PhoneCallService.InboundRouting r = routing.get();
        callService.recordInbound(r.tenantId(), vapiCallId, vapiNumberId, customerNumber, r.assistantId());
        return Map.of("assistantId", r.assistantId());
    }

    private Map<String, Object> handleToolCalls(JsonNode message) {
        String vapiCallId = text(message.path("call"), "id");
        String vapiNumberId = text(message.path("phoneNumber"), "id");
        String tenantId = callService.resolveTenant(vapiCallId, vapiNumberId).orElse(null);

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode tc : message.path("toolCallList")) {
            String id = text(tc, "id");
            if (id == null) continue;
            // Vapi nests the call under "function" ({name, arguments}); accept the flat form too.
            JsonNode fn = tc.has("function") ? tc.path("function") : tc;
            String name = text(fn, "name");
            Map<String, Object> args = asMap(fn.get("arguments"));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("toolCallId", id);
            if (tenantId == null) {
                entry.put("error", "Call could not be associated with a tenant");
            } else {
                VapiToolHandler.ToolResult res = toolHandler.handle(tenantId, name, args, vapiCallId);
                if (res != null && res.ok()) {
                    entry.put("result", res.result());
                } else {
                    entry.put("error", res != null && res.error() != null ? res.error() : "Tool failed");
                }
            }
            results.add(entry);
        }
        return Map.of("results", results);
    }

    private void handleStatusUpdate(JsonNode message) {
        String vapiCallId = text(message.path("call"), "id");
        // Vapi puts the new status at message.status; fall back to message.call.status.
        String status = text(message, "status");
        if (status == null) status = text(message.path("call"), "status");
        callService.applyStatus(vapiCallId, status);
    }

    private void handleEndReport(JsonNode message) {
        JsonNode call = message.path("call");
        JsonNode artifact = message.path("artifact");
        JsonNode analysis = message.path("analysis");

        String vapiCallId = text(call, "id");
        String endedReason = firstText(message, "endedReason", call, "endedReason");
        String transcript = firstText(message, "transcript", artifact, "transcript");
        String recordingUrl = firstText(message, "recordingUrl", artifact, "recordingUrl");
        String summary = firstText(message, "summary", analysis, "summary");
        Double cost = doubleOrNull(message.get("cost"));
        Map<String, Object> structured = asMap(analysis.get("structuredData"));
        if (structured.isEmpty()) structured = asMap(analysis.get("structuredOutput"));

        callService.applyEndReport(vapiCallId, new PhoneCallService.EndReport(
                endedReason, transcript, recordingUrl, summary, cost,
                structured.isEmpty() ? null : structured));
    }

    /* ── JSON helpers ───────────────────────────────────────────── */

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    /** First non-null of {@code a.fieldA} then {@code b.fieldB}. */
    private static String firstText(JsonNode a, String fieldA, JsonNode b, String fieldB) {
        String v = text(a, fieldA);
        return v != null ? v : text(b, fieldB);
    }

    private static Double doubleOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    /** A JSON object node (or a JSON string holding an object) → a Map; {@code {}} otherwise. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        try {
            if (node.isObject()) return MAPPER.convertValue(node, Map.class);
            if (node.isTextual()) {
                String s = node.asText();
                if (s.isBlank()) return Map.of();
                JsonNode parsed = MAPPER.readTree(s);
                if (parsed.isObject()) return MAPPER.convertValue(parsed, Map.class);
            }
        } catch (Exception ignored) {
            // not a parseable object — return empty
        }
        return Map.of();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
