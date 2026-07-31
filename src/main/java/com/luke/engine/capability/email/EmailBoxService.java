package com.luke.engine.capability.email;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registers and manages a tenant's email <b>boxes</b> — addresses on its verified sender
 * domain, in one of two directions:
 *
 * <ul>
 *   <li><b>OUTBOUND</b> — creates a dedicated Postmark message stream (per-box stats/reputation)
 *       on the tenant's server, then persists the box as a send-from identity.</li>
 *   <li><b>INBOUND</b> — ensures the tenant's server points at our public inbound webhook
 *       ({@code /api/public/email/inbound/{token}}) and persists the box; mail routed to it is
 *       stored and (optionally) correlated to a workflow. Custom-domain inbound needs the sender
 *       domain MX'd to Postmark; {@code routingKey} also supports {@code +hash@inbound.postmarkapp.com}.</li>
 * </ul>
 *
 * All operations are tenant-scoped and require the tenant to have completed email setup
 * (a provisioned {@link EmailServer}).
 */
@Service
public class EmailBoxService {

    private static final Logger log = LoggerFactory.getLogger(EmailBoxService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailBoxRepository boxes;
    private final EmailServerRepository servers;
    private final EmailServerService serverService;
    private final PostmarkClient postmark;
    private final PostmarkAccountClient accountClient;
    /** Repository, not EmailRoutingRuleService — that service depends on EmailBoxRepository,
     *  and going through it would put a cycle in the bean graph for no gain. */
    private final EmailRoutingRuleRepository routingRules;

    /** Public base URL Postmark POSTs inbound mail to (e.g. https://authdev.lukeflow.com). */
    @Value("${luke.email.inbound.public-base-url:}")
    private String inboundPublicBaseUrl;

    public EmailBoxService(EmailBoxRepository boxes, EmailServerRepository servers,
            EmailServerService serverService, PostmarkClient postmark, PostmarkAccountClient accountClient,
            EmailRoutingRuleRepository routingRules) {
        this.boxes = boxes;
        this.servers = servers;
        this.serverService = serverService;
        this.postmark = postmark;
        this.accountClient = accountClient;
        this.routingRules = routingRules;
    }

    /** Registration input. {@code localPart} is the part before {@code @}; the domain is the tenant's. */
    public record RegisterRequest(String direction, String localPart, String displayName,
                                  String streamType, Boolean workflowTrigger) {}

    /** What registration returns — the box plus (inbound) the webhook + Postmark inbound address. */
    public record RegisterResult(EmailBox box, String inboundWebhookUrl, String postmarkInboundAddress,
                                 String warning) {}

    public List<EmailBox> list(String tenantId) {
        return boxes.findByTenantIdOrderByCreatedAtAsc(tenantId);
    }

    public RegisterResult register(String tenantId, RegisterRequest req) {
        EmailServer server = servers.findByTenantId(tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,
                        "Set up email (verify your domain) before registering boxes"));

        EmailBox.Direction direction = parseDirection(req.direction());
        String localPart = normalizeLocalPart(req.localPart());
        String address = localPart + "@" + server.getSenderDomain();

        if (boxes.existsByTenantIdAndDirectionAndAddress(tenantId, direction.name(), address)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + direction.name().toLowerCase() + " box already exists for " + address);
        }

        EmailBox box = new EmailBox();
        box.setId(UUID.randomUUID().toString());
        box.setTenantId(tenantId);
        box.setDirection(direction.name());
        box.setAddress(address);
        box.setLocalPart(localPart);
        box.setDisplayName(isBlank(req.displayName()) ? null : req.displayName().trim());

        if (direction == EmailBox.Direction.OUTBOUND) {
            return registerOutbound(server, box, req);
        }
        return registerInbound(server, box, req);
    }

    private RegisterResult registerOutbound(EmailServer server, EmailBox box, RegisterRequest req) {
        String token = serverService.resolveServerToken(server.getTenantId());
        if (isBlank(token)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No Postmark server token for this tenant — re-run email setup");
        }
        String streamType = normalizeStreamType(req.streamType());
        String streamId = streamId(box.getLocalPart());
        String streamName = box.getDisplayName() != null ? box.getDisplayName() : box.getLocalPart();
        PostmarkClient.StreamResult res = postmark.createMessageStream(token, streamId, streamName, streamType);
        if (!res.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not create Postmark stream: " + res.error());
        }
        box.setPostmarkStreamId(res.streamId());
        box.setWorkflowTrigger(false); // N/A for outbound
        boxes.save(box);
        log.info("Registered OUTBOUND box {} (stream {}) for tenant {}", box.getAddress(), res.streamId(), server.getTenantId());
        return new RegisterResult(box, null, null, null);
    }

    private RegisterResult registerInbound(EmailServer server, EmailBox box, RegisterRequest req) {
        box.setRoutingKey(box.getLocalPart()); // MailboxHash for +hash@inbound.postmarkapp.com
        box.setWorkflowTrigger(req.workflowTrigger() == null || req.workflowTrigger());

        // Ensure the tenant server has an inbound token + is pointed at our webhook (once).
        String token = server.getInboundHookToken();
        if (isBlank(token)) {
            token = newToken();
            server.setInboundHookToken(token);
            servers.save(server);
        }
        String webhookUrl = null;
        String postmarkInbound = null;
        String warning = null;
        if (isBlank(inboundPublicBaseUrl)) {
            warning = "Inbound webhook base URL is not configured (set EMAIL_INBOUND_PUBLIC_BASE_URL); "
                    + "the box is registered but Postmark isn't wired to it yet.";
        } else if (server.getPostmarkServerId() == null) {
            warning = "No Postmark server id on record for this tenant; cannot set the inbound hook.";
        } else {
            webhookUrl = inboundPublicBaseUrl.replaceAll("/+$", "") + "/api/public/email/inbound/" + token;
            PostmarkAccountClient.InboundHookResult hook =
                    accountClient.setInboundHook(server.getPostmarkServerId(), webhookUrl);
            if (hook.ok()) {
                postmarkInbound = hook.inboundAddress();
            } else {
                warning = "Box registered, but setting the Postmark inbound hook failed: " + hook.error();
            }
        }
        boxes.save(box);
        log.info("Registered INBOUND box {} (workflowTrigger={}) for tenant {}",
                box.getAddress(), box.isWorkflowTrigger(), server.getTenantId());
        return new RegisterResult(box, webhookUrl, postmarkInbound, warning);
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(String tenantId, String id) {
        EmailBox box = boxes.findByIdAndTenantId(id, tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Box not found"));
        // Rules scoped to this box go with it. Left behind they would be dead rows that still
        // cost a comparison on every inbound message and would silently reactivate if the same
        // box id were ever reissued. Rules with a null boxId are tenant-wide and stay.
        routingRules.deleteByTenantIdAndBoxId(tenantId, box.getId());
        // We leave the Postmark stream in place (Postmark keeps message history); just drop our row.
        boxes.delete(box);
    }

    /** Resolve an inbound recipient (address + optional MailboxHash) to a registered inbound box. */
    public Optional<EmailBox> resolveInbound(String tenantId, String toAddress, String mailboxHash) {
        if (!isBlank(toAddress)) {
            Optional<EmailBox> byAddr = boxes.findByTenantIdAndDirectionAndAddress(
                    tenantId, EmailBox.Direction.INBOUND.name(), toAddress.trim().toLowerCase());
            if (byAddr.isPresent()) return byAddr;
        }
        if (!isBlank(mailboxHash)) {
            return boxes.findByTenantIdAndDirectionAndRoutingKey(
                    tenantId, EmailBox.Direction.INBOUND.name(), mailboxHash.trim());
        }
        return Optional.empty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static EmailBox.Direction parseDirection(String d) {
        if (d == null) throw badRequest("direction is required (INBOUND or OUTBOUND)");
        try {
            return EmailBox.Direction.valueOf(d.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw badRequest("direction must be INBOUND or OUTBOUND");
        }
    }

    private static String normalizeLocalPart(String lp) {
        if (isBlank(lp)) throw badRequest("localPart is required (e.g. 'support')");
        String v = lp.trim().toLowerCase();
        // Take only the local part if a full address was pasted.
        int at = v.indexOf('@');
        if (at >= 0) v = v.substring(0, at);
        if (!v.matches("[a-z0-9._+-]{1,64}")) {
            throw badRequest("localPart may contain only letters, digits and . _ + -");
        }
        return v;
    }

    private static String normalizeStreamType(String t) {
        if (isBlank(t)) return "Transactional";
        String v = t.trim();
        return switch (v.toLowerCase()) {
            case "broadcasts", "broadcast" -> "Broadcasts";
            case "transactional" -> "Transactional";
            default -> throw badRequest("streamType must be Transactional or Broadcasts");
        };
    }

    /** Postmark stream id: lowercase alphanumeric + hyphens, unique per server. */
    private static String streamId(String localPart) {
        String slug = localPart.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "stream-" + Integer.toHexString(RANDOM.nextInt()) : slug;
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
