package com.luke.engine.capability.email;

import com.luke.engine.capability.secrets.ManagedBy;
import com.luke.engine.capability.secrets.SecretStore;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns per-tenant Postmark Servers: provisioning one company's dedicated server
 * (Account API), and — on every send — resolving which token to use and verifying
 * the sender is on that company's own domain.
 *
 * <p>Sender trust (phase 1) is by convention: a company "acme" sends from the
 * subdomain {@code acme.<base-domain>} (e.g. {@code acme.lukeflow.com}). The From's
 * domain must equal the provisioned server's {@code senderDomain}; anything else is
 * rejected, so one tenant can never send as another. A later phase will replace the
 * convention with a verified domain registry.
 */
@Service
public class EmailServerService {

    private static final Logger log = LoggerFactory.getLogger(EmailServerService.class);

    /** Secret-store key under which each tenant's Postmark send token is stored. */
    private static final String POSTMARK_TOKEN_SECRET = "postmark.server-token";

    private final EmailServerRepository servers;
    private final PostmarkAccountClient accountClient;
    private final SecretStore secretStore;

    /** Platform base domain for sender subdomains, e.g. "lukeflow.com". Blank → enforcement off (dev). */
    @Value("${luke.email.sender.base-domain:}")
    private String baseDomain;

    /** Local-part for a company's default sender, e.g. "no-reply" → no-reply@acme.lukeflow.com. */
    @Value("${luke.email.sender.default-local-part:no-reply}")
    private String defaultLocalPart;

    /** Fallback send token when a tenant has no provisioned server (dev / single-server mode). */
    @Value("${luke.email.postmark.server-token:}")
    private String fallbackServerToken;

    /** Global fallback From when there's no server and no per-request sender. */
    @Value("${luke.email.postmark.default-from:}")
    private String fallbackFrom;

    /** Global fallback message stream. */
    @Value("${luke.email.postmark.message-stream:outbound}")
    private String fallbackStream;

    public EmailServerService(EmailServerRepository servers, PostmarkAccountClient accountClient,
                              SecretStore secretStore) {
        this.servers = servers;
        this.accountClient = accountClient;
        this.secretStore = secretStore;
    }

    /* ── provisioning ───────────────────────────────────────── */

    public record ProvisionRequest(String slug, String name, String senderDomain,
                                   String fromAddress, String messageStream, String color) {}

    /**
     * Provision (idempotently fails on re-provision) a Postmark Server for a tenant.
     * The company slug derives the sender subdomain ({@code slug.<base-domain>})
     * unless an explicit {@code senderDomain} is given. Conflicts if the tenant
     * already has one.
     */
    public EmailServer provision(String tenantId, ProvisionRequest req) {
        if (servers.existsByTenantId(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An email server is already provisioned for this tenant");
        }
        String slug = normalizeSlug(req.slug());
        if (slug.isBlank()) throw bad("slug is required");

        String senderDomain = !isBlank(req.senderDomain())
                ? req.senderDomain().trim().toLowerCase(Locale.ROOT)
                : deriveSenderDomain(slug);
        if (senderDomain.isBlank()) {
            throw bad("senderDomain is required (no luke.email.sender.base-domain is configured to derive it)");
        }

        String defaultFrom = !isBlank(req.fromAddress())
                ? req.fromAddress().trim()
                : defaultLocalPart + "@" + senderDomain;
        if (!domainOf(defaultFrom).equals(senderDomain)) {
            throw bad("fromAddress " + defaultFrom + " is not on the sender domain " + senderDomain);
        }

        String serverName = !isBlank(req.name()) ? req.name().trim() : "Lukeflow — " + slug;
        return createAndSave(tenantId, slug, senderDomain, defaultFrom, serverName, req.messageStream(), req.color());
    }

    /**
     * Provision the company's server as the result of a passed OTP verification. The
     * slug and sender domain are derived from the <em>verified</em> corporate domain
     * (the proven thing), not the free-text org name. Idempotent: if a server already
     * exists, it is simply stamped with the verification and returned.
     */
    public EmailServer completeVerification(String tenantId, String orgName, String verifiedEmail) {
        String verifiedDomain = OrgDomainMatcher.domainOf(verifiedEmail);
        String slug = normalizeSlug(OrgDomainMatcher.domainRoot(verifiedDomain));
        if (slug.isBlank()) throw bad("Could not derive a company slug from " + verifiedEmail);

        EmailServer server = servers.findByTenantId(tenantId).orElse(null);
        if (server == null) {
            // In prod the sender subdomain is slug.<base-domain>; with no base domain
            // configured (dev) we fall back to the verified corporate domain itself.
            String senderDomain = !isBlank(baseDomain) ? deriveSenderDomain(slug) : verifiedDomain;
            String defaultFrom = defaultLocalPart + "@" + senderDomain;
            String serverName = !isBlank(orgName) ? orgName.trim() : "Lukeflow — " + slug;
            server = createAndSave(tenantId, slug, senderDomain, defaultFrom, serverName, null, null);
        }
        server.setVerifiedEmail(verifiedEmail);
        server.setVerifiedDomain(verifiedDomain);
        server.setVerifiedAt(LocalDateTime.now());
        return servers.save(server);
    }

    /** Create the Postmark server (Account API) and persist the row. Postmark failure → 502. */
    private EmailServer createAndSave(String tenantId, String slug, String senderDomain, String defaultFrom,
                                      String serverName, String messageStream, String color) {
        PostmarkAccountClient.CreateServerResult res = accountClient.createServer(serverName, color);
        if (!res.ok()) {
            // Provisioning is an explicit action (not best-effort like a send): a
            // Postmark failure is a hard error — nothing was created to record.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Postmark could not create the server: " + res.error());
        }
        // The send token is a secret — stash it encrypted in the secret store keyed
        // by tenant, never on the EmailServer row.
        secretStore.put(tenantId, POSTMARK_TOKEN_SECRET, res.serverToken(), ManagedBy.SYSTEM);

        EmailServer server = new EmailServer();
        server.setTenantId(tenantId);
        server.setCompanySlug(slug);
        server.setSenderDomain(senderDomain);
        server.setDefaultFrom(defaultFrom);
        server.setPostmarkServerId(res.serverId());
        server.setServerName(serverName);
        server.setMessageStream(!isBlank(messageStream) ? messageStream.trim() : fallbackStream);
        servers.save(server);
        log.info("Provisioned Postmark server {} (id {}) for tenant {} on domain {}",
                serverName, res.serverId(), tenantId, senderDomain);
        return server;
    }

    public Optional<EmailServer> find(String tenantId) {
        return servers.findByTenantId(tenantId);
    }

    public EmailServer require(String tenantId) {
        return servers.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No email server provisioned for this tenant"));
    }

    /* ── send-time resolution + sender validation ───────────── */

    /**
     * Resolve just the Postmark Server token for {@code tenantId} — the per-tenant
     * token from the secret store, or the global fallback when no server is
     * provisioned. Reuses the same lookup as {@link #resolveSendContext} (no From /
     * domain enforcement, since publishing a template is not a send). If the tenant
     * has no provisioned server and no fallback is configured, fails with a clear
     * 409 so the caller can prompt "connect email first" rather than crashing.
     */
    public String resolveServerToken(String tenantId) {
        Optional<EmailServer> maybe = servers.findByTenantId(tenantId);
        if (maybe.isEmpty() && isBlank(fallbackServerToken)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No email server is provisioned for this company; connect email before publishing a template");
        }
        String token = maybe.isPresent()
                ? secretStore.get(tenantId, POSTMARK_TOKEN_SECRET).orElse(null)
                : fallbackServerToken;
        if (isBlank(token)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email sending is not configured (no Postmark server token available)");
        }
        return token;
    }

    /** The token to send with, the resolved From, and the default message stream. */
    public record SendContext(String serverToken, String from, String messageStream) {}

    /**
     * Resolve how to send for {@code tenantId}: pick the per-tenant server token
     * (or the global fallback), settle the From (request value or the company
     * default), and — when a base domain is configured — verify the From is on the
     * company's own sender domain.
     */
    public SendContext resolveSendContext(String tenantId, String requestedFrom) {
        Optional<EmailServer> maybe = servers.findByTenantId(tenantId);
        boolean enforce = !isBlank(baseDomain);

        if (enforce && maybe.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No email server is provisioned for this company; provision one before sending");
        }

        // Per-tenant token comes from the secret store; the fallback token is for
        // tenants without a provisioned server (dev / OTP send before provisioning).
        String token = maybe.isPresent()
                ? secretStore.get(tenantId, POSTMARK_TOKEN_SECRET).orElse(null)
                : fallbackServerToken;
        if (isBlank(token)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email sending is not configured (no Postmark server token available)");
        }

        String from = !isBlank(requestedFrom) ? requestedFrom.trim()
                : maybe.map(EmailServer::getDefaultFrom).orElse(fallbackFrom);
        if (isBlank(from)) {
            throw bad("from is required (and no default sender is configured)");
        }

        if (enforce) {
            String allowed = maybe.get().getSenderDomain();
            if (!domainOf(from).equals(allowed)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Sender " + from + " is not on your company domain " + allowed
                                + " — you may only send as your own company");
            }
        }

        String stream = maybe.map(EmailServer::getMessageStream).orElse(fallbackStream);
        return new SendContext(token, from, stream);
    }

    /* ── helpers ────────────────────────────────────────────── */

    private String deriveSenderDomain(String slug) {
        return isBlank(baseDomain) ? "" : slug + "." + baseDomain.trim().toLowerCase(Locale.ROOT);
    }

    /** Lowercase, trim, and strip anything but [a-z0-9-] so the slug is a safe DNS label. */
    private static String normalizeSlug(String slug) {
        if (slug == null) return "";
        return slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
    }

    /** The domain part of an email address, lowercased; "" if there's no '@'. */
    private static String domainOf(String email) {
        if (email == null) return "";
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
