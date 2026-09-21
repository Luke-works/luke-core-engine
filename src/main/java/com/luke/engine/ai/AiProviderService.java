package com.luke.engine.ai;

import com.luke.engine.audit.AdminAuditService;
import com.luke.engine.capability.secrets.ManagedBy;
import com.luke.engine.capability.secrets.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A workspace's own AI provider account: connect it, verify it, use it, remove it.
 *
 * <p>The design mirrors form payments. Lukeflow enables the capability and takes nothing for
 * it; the workspace brings the account that gets billed. What differs is that an LLM provider
 * has no OAuth handshake to hide behind — the workspace pastes an API key, so we do hold
 * secret material, and every decision here follows from that:
 *
 * <ul>
 *   <li>The key goes straight into {@code luke_secrets} (AES-256-GCM, per-tenant, rotatable
 *       master key). {@link AiProvider} keeps only a fingerprint and the last four characters.</li>
 *   <li>Nothing returns the key. {@link #view} is what a human may see; {@link #resolve} is
 *       server-side only and exists solely to attach the key to an outbound agent turn.</li>
 *   <li>A key is verified against the provider before it is stored, so a workspace learns it
 *       mistyped the key on the connect page rather than on their next form build.</li>
 *   <li>"The provider refused this key" and "we couldn't reach the provider" are different
 *       outcomes. Only the first marks a workspace {@code INVALID}; our own outage must never
 *       switch off a working workspace.</li>
 * </ul>
 */
@Service
public class AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);

    private final AiProviderRepository repository;
    private final SecretStore secrets;
    private final AiProviderProbe probe;
    private final AdminAuditService audit;

    public AiProviderService(AiProviderRepository repository, SecretStore secrets,
                             AiProviderProbe probe, AdminAuditService audit) {
        this.repository = repository;
        this.secrets = secrets;
        this.probe = probe;
        this.audit = audit;
    }

    /** A workspace's credential, resolved for one outbound agent turn. Never leaves this service. */
    public record Resolved(String provider, String apiKey, String model) {}

    /* ── reading ──────────────────────────────────────────────────────────── */

    /**
     * What the connect page shows. Contains no key — {@code keyLast4} is the only fragment,
     * and it is there so a human can tell which of their keys is in use.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable).orElse(null);
        out.put("connected", row != null);
        out.put("providers", AiProviderCatalog.all().stream().map(p -> Map.of(
                "id", p.id(), "label", p.label(),
                "defaultModel", p.defaultModel(), "consoleUrl", p.consoleUrl())).toList());

        AiProvider any = repository.findById(tenantId).orElse(null);
        if (any == null) return out;

        out.put("status", any.getStatus());
        out.put("provider", any.getProvider());
        AiProviderCatalog.find(any.getProvider())
                .ifPresent(p -> out.put("providerLabel", p.label()));
        // The effective model: what they picked, or what the provider falls back to. The page
        // shows this either way, so "no model chosen" never reads as "no model".
        out.put("model", any.getModel());
        out.put("effectiveModel", effectiveModel(any));
        out.put("keyLast4", any.getKeyLast4());
        out.put("connectedAt", any.getConnectedAt());
        out.put("connectedBy", any.getConnectedBy());
        out.put("verifiedAt", any.getVerifiedAt());
        out.put("lastError", any.getLastError());
        return out;
    }

    private String effectiveModel(AiProvider row) {
        if (row.getModel() != null && !row.getModel().isBlank()) return row.getModel();
        return AiProviderCatalog.find(row.getProvider())
                .map(AiProviderCatalog.Provider::defaultModel).orElse(null);
    }

    /**
     * The credential to attach to an agent turn, or empty when this workspace cannot run one.
     *
     * <p>Empty covers every "no" — never connected, disconnected, marked invalid, or the row
     * exists but its secret has gone. The caller turns that into the single 402 the UI knows
     * how to render; it must not need to distinguish them.
     */
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String tenantId) {
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable).orElse(null);
        if (row == null) return Optional.empty();
        return secrets.get(tenantId, AiProvider.SECRET_NAME)
                .filter(key -> !key.isBlank())
                .map(key -> new Resolved(row.getProvider(), key, effectiveModel(row)));
    }

    /* ── connecting ───────────────────────────────────────────────────────── */

    /**
     * Store and start using a workspace's provider key.
     *
     * <p>Verified before it is stored: an unusable key must never become the workspace's
     * configured state, because the next thing anyone does is try to build a form with it.
     */
    @Transactional
    public Map<String, Object> connect(String tenantId, String userId, String providerId,
                                       String apiKey, String model) {
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose one of: " + AiProviderCatalog.all().stream()
                                .map(AiProviderCatalog.Provider::label).toList()));
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste your " + provider.label() + " API key.");
        }
        if (!AiProviderCatalog.looksLikeKeyFor(provider, key)) {
            // Catches the common paste error (two providers' keys side by side in a password
            // manager) with a better message than the provider's bare 401.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't look like a " + provider.label() + " key — they start with \""
                            + provider.keyPrefix() + "\".");
        }

        AiProviderProbe.Result result = probe.verify(provider, key);
        if (result.outcome() == AiProviderProbe.Outcome.INVALID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.message());
        }
        if (result.outcome() == AiProviderProbe.Outcome.UNREACHABLE) {
            // Storing an unverified key would leave the workspace looking connected while
            // every turn fails. 503 says "try again", which is what we mean.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.message());
        }

        String chosen = normalizeModel(model, result.models(), provider);

        // Secret first: if this fails, we have not told anyone they are connected.
        secrets.put(tenantId, AiProvider.SECRET_NAME, key, ManagedBy.TENANT);

        AiProvider row = repository.findById(tenantId).orElseGet(() -> new AiProvider(tenantId));
        boolean rotation = AiProvider.CONNECTED.equals(row.getStatus())
                && fingerprint(key).equals(row.getKeyFingerprint());
        row.setProvider(provider.id());
        row.setModel(chosen);
        row.setStatus(AiProvider.CONNECTED);
        row.setKeyLast4(lastFour(key));
        row.setKeyFingerprint(fingerprint(key));
        row.setConnectedBy(userId);
        row.setConnectedAt(LocalDateTime.now());
        row.setVerifiedAt(LocalDateTime.now());
        row.setDisconnectedAt(null);
        row.setLastError(null);
        repository.save(row);

        audit.record(rotation ? "ai.provider.updated" : "ai.provider.connected", "ai_provider", tenantId,
                tenantId, userId, false, Map.of("provider", provider.id(), "model", String.valueOf(chosen)));
        log.info("ai: tenant {} connected {} (model={})", tenantId, provider.id(), chosen);

        Map<String, Object> out = new LinkedHashMap<>(view(tenantId));
        out.put("models", result.models());
        return out;
    }

    /**
     * The models this workspace's key may actually use, for the model picker.
     *
     * <p>Read live from the provider rather than hardcoded, so the list reflects what their
     * account can run. A probe that comes back empty is not an error: the workspace simply
     * keeps the provider default.
     */
    @Transactional(readOnly = true)
    public List<String> models(String tenantId) {
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This workspace hasn't connected an AI provider."));
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(row.getProvider())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Unknown provider on this workspace."));
        String key = secrets.get(tenantId, AiProvider.SECRET_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored key for this workspace."));
        return probe.verify(provider, key).models();
    }

    /** Change the model without re-pasting the key. */
    @Transactional
    public Map<String, Object> chooseModel(String tenantId, String userId, String model) {
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This workspace hasn't connected an AI provider."));
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(row.getProvider())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Unknown provider on this workspace."));
        row.setModel(normalizeModel(model, List.of(), provider));
        repository.save(row);
        audit.record("ai.provider.model-changed", "ai_provider", tenantId, tenantId, userId, false,
                Map.of("model", String.valueOf(row.getModel())));
        return view(tenantId);
    }

    /**
     * Re-check the stored key against the provider and record what we learn.
     *
     * <p>Used by the connect page and after a turn fails. An UNREACHABLE outcome changes
     * nothing — see {@link #markInvalid}.
     */
    @Transactional
    public Map<String, Object> verify(String tenantId) {
        AiProvider row = repository.findById(tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "This workspace hasn't connected an AI provider."));
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(row.getProvider())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Unknown provider on this workspace."));
        String key = secrets.get(tenantId, AiProvider.SECRET_NAME).orElse(null);
        if (key == null || key.isBlank()) {
            // The row says connected but the secret is gone — treat it as disconnected rather
            // than leaving a workspace that can never run a turn looking healthy.
            row.setStatus(AiProvider.DISCONNECTED);
            row.setLastError("The stored key is missing. Connect your provider again.");
            repository.save(row);
            return view(tenantId);
        }

        AiProviderProbe.Result result = probe.verify(provider, key);
        switch (result.outcome()) {
            case OK -> {
                row.setStatus(AiProvider.CONNECTED);
                row.setVerifiedAt(LocalDateTime.now());
                row.setLastError(null);
            }
            case INVALID -> {
                row.setStatus(AiProvider.INVALID);
                row.setLastError(result.message());
            }
            case UNREACHABLE -> { /* our problem, not theirs — leave the row exactly as it was */ }
        }
        repository.save(row);
        return view(tenantId);
    }

    /**
     * Record that the provider refused this workspace's key during a turn.
     *
     * <p>Called only for an authentication refusal (401/403). A timeout, a 5xx or a 429 must
     * never land here: they say nothing about the key, and switching a workspace off because
     * the provider had a bad minute is the worst failure mode this feature has.
     */
    @Transactional
    public void markInvalid(String tenantId, String message) {
        repository.findById(tenantId).ifPresent(row -> {
            if (!AiProvider.CONNECTED.equals(row.getStatus())) return;
            row.setStatus(AiProvider.INVALID);
            row.setLastError(message);
            repository.save(row);
            audit.record("ai.provider.invalid", "ai_provider", tenantId, tenantId, "system", false,
                    Map.of("provider", String.valueOf(row.getProvider())));
            log.warn("ai: tenant {} provider {} rejected the stored key", tenantId, row.getProvider());
        });
    }

    /* ── disconnecting ────────────────────────────────────────────────────── */

    /**
     * Forget a workspace's key.
     *
     * <p>The secret is deleted; the row survives as the audit trail of who connected what and
     * when. Nothing about an AI turn is long-running, so unlike a payment disconnect there is
     * nothing to wind down first.
     */
    @Transactional
    public Map<String, Object> disconnect(String tenantId, String userId) {
        AiProvider row = repository.findById(tenantId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "This workspace hasn't connected an AI provider."));
        secrets.delete(tenantId, AiProvider.SECRET_NAME);
        row.setStatus(AiProvider.DISCONNECTED);
        row.setDisconnectedAt(LocalDateTime.now());
        row.setKeyFingerprint(null);
        row.setKeyLast4(null);
        row.setLastError(null);
        repository.save(row);
        audit.record("ai.provider.disconnected", "ai_provider", tenantId, tenantId, userId, false,
                Map.of("provider", String.valueOf(row.getProvider())));
        return view(tenantId);
    }

    /** Called when a tenant is purged: the key must not outlive the workspace. */
    @Transactional
    public void forget(String tenantId) {
        secrets.delete(tenantId, AiProvider.SECRET_NAME);
        repository.deleteById(tenantId);
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    /**
     * A blank choice means "use the provider default", stored as null so the default tracks
     * the catalog instead of freezing whatever it was on the day they connected.
     */
    private String normalizeModel(String model, List<String> available, AiProviderCatalog.Provider provider) {
        String chosen = model == null ? "" : model.trim();
        if (chosen.isEmpty()) return null;
        if (chosen.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That model name is too long.");
        }
        if (!available.isEmpty() && !available.contains(chosen)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your " + provider.label() + " account can't use \"" + chosen + "\".");
        }
        return chosen;
    }

    private static String lastFour(String key) {
        return key.length() >= 4 ? key.substring(key.length() - 4) : "";
    }

    /** SHA-256 of the key — lets us recognise a rotation without storing the key twice. */
    private static String fingerprint(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {  // SHA-256 is mandated by the JDK
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
