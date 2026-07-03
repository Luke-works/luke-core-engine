package com.luke.engine.workflow.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
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
 * Receives Nango webhooks — the inbound rail's auth events (WF-10). Public by design
 * ({@code /api/public/**}; see {@link com.luke.engine.config.ApiAuthFilter}) and
 * authenticated by the {@code X-Nango-Signature} shared-secret HMAC via
 * {@link NangoWebhookVerifier} before anything is read.
 *
 * <p>The raw body is taken as a {@code String} so the signature is checked over the exact
 * received bytes (re-serializing JSON would break the hash). Deliveries are deduped in
 * {@link IntegrationWebhookLog} (Nango is at-least-once). Handling never 500s — we log and
 * acknowledge so Nango doesn't hammer retries.
 *
 * <p>WF-10 handles {@code type=auth}: success → PENDING connection becomes ACTIVE (correlated
 * by the end-user id we passed at connect time = our row id); failure → NEEDS_RECONNECT.
 * Sync/forward event types are accepted and ignored here (wired in WF-12).
 */
@RestController
@RequestMapping("/api/public/integrations/nango")
public class NangoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(NangoWebhookController.class);

    private final NangoWebhookVerifier verifier;
    private final ConnectionService connections;
    private final IntegrationConnectionRepository connectionRepo;
    private final IntegrationEventOutboxRepository eventOutbox;
    private final IntegrationWebhookLogRepository webhookLog;
    private final ObjectMapper mapper;

    public NangoWebhookController(NangoWebhookVerifier verifier, ConnectionService connections,
            IntegrationConnectionRepository connectionRepo, IntegrationEventOutboxRepository eventOutbox,
            IntegrationWebhookLogRepository webhookLog, ObjectMapper mapper) {
        this.verifier = verifier;
        this.connections = connections;
        this.connectionRepo = connectionRepo;
        this.eventOutbox = eventOutbox;
        this.webhookLog = webhookLog;
        this.mapper = mapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-Nango-Signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) {

        boolean signatureOk = verifier.verify(signature, rawBody);
        if (!signatureOk) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid webhook signature"));
        }
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.ok(Map.of());
        }

        String deliveryId = signature != null ? signature : sha256Hex(rawBody);
        if (deliveryId != null && webhookLog.existsById(deliveryId)) {
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }

        String type = null;
        try {
            JsonNode root = mapper.readTree(rawBody);
            type = text(root, "type");
            if ("auth".equals(type)) {
                handleAuth(root);
            } else if ("sync".equals(type) || "forward".equals(type)) {
                enqueueEvent(type, root, rawBody);
            }
            // other types: accepted and ignored.
        } catch (Exception e) {
            // Never 500 a webhook on our own bug — log and acknowledge.
            log.warn("Nango webhook '{}' handling errored: {}", type, e.getMessage());
        }

        if (deliveryId != null) {
            try {
                webhookLog.save(new IntegrationWebhookLog(deliveryId, type, signatureOk));
            } catch (Exception e) {
                log.debug("webhook-log save skipped (likely concurrent duplicate): {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Enqueue a sync/forward event onto the inbound outbox for correlation (WF-12). The Nango
     * {@code connectionId} resolves the owning tenant; the BPMN message name is
     * {@code integrations.<model|syncName|type>} — matching how {@code WorkflowCompiler} names a
     * wait-event's message ({@code capability.type}).
     */
    private void enqueueEvent(String type, JsonNode root, String rawBody) {
        String nangoConnectionId = text(root, "connectionId");
        IntegrationConnection conn = nangoConnectionId == null ? null
                : connectionRepo.findFirstByNangoConnectionId(nangoConnectionId).orElse(null);
        if (conn == null) {
            log.warn("Nango {} event for unknown connection {} — dropped", type, nangoConnectionId);
            return;
        }
        String subject = firstNonBlank(text(root, "model"), text(root, "syncName"), type);
        String messageName = "integrations." + subject;
        eventOutbox.save(new IntegrationEventOutbox(
                conn.getTenantId(), messageName, null, conn.getId(), type, rawBody));
    }

    private void handleAuth(JsonNode root) {
        boolean success = root.path("success").asBoolean(true);
        String nangoConnectionId = text(root, "connectionId");
        // The end-user id we passed at connect time IS our connection row id.
        String rowId = firstNonBlank(
                text(root.path("endUser"), "endUserId"),
                text(root.path("endUser"), "id"),
                text(root, "endUserId"));
        if (rowId == null) {
            log.warn("Nango auth webhook without a correlatable end-user id (connectionId={})", nangoConnectionId);
            return;
        }
        try {
            if (success) {
                connections.markActive(rowId, nangoConnectionId, null, null);
            } else {
                connections.markNeedsReconnect(rowId, "Authorization failed");
            }
        } catch (IntegrationException e) {
            log.warn("Nango auth webhook for unknown connection {}: {}", rowId, e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
