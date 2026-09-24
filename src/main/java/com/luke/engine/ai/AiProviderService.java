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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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

    /** How long a workspace's model list is reused before we ask the provider again. */
    private static final long MODELS_TTL_NANOS = java.time.Duration.ofMinutes(5).toNanos();

    private record CachedModels(List<String> models, long readAt) {
        boolean isStale() {
            return System.nanoTime() - readAt > MODELS_TTL_NANOS;
        }
    }

    private final Map<String, CachedModels> modelCache = new ConcurrentHashMap<>();
    private final Map<String, Lock> modelLocks = new ConcurrentHashMap<>();

    private final AiProviderRepository repository;
    private final AiUserPreferenceRepository preferences;
    private final SecretStore secrets;
    private final AiProviderProbe probe;
    private final AdminAuditService audit;

    public AiProviderService(AiProviderRepository repository, AiUserPreferenceRepository preferences,
                             SecretStore secrets, AiProviderProbe probe, AdminAuditService audit) {
        this.repository = repository;
        this.preferences = preferences;
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
                "id", p.id(), "label", p.label(), "defaultModel", p.defaultModel(),
                // The connect page shows this as the key field's placeholder, so a wrong-provider
                // paste is obvious before it is submitted.
                "keyPrefix", p.keyPrefix(), "consoleUrl", p.consoleUrl())).toList());

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
    public Optional<Resolved> resolve(String tenantId, String userId) {
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable).orElse(null);
        if (row == null) return Optional.empty();
        return secrets.get(tenantId, AiProvider.SECRET_NAME)
                .filter(key -> !key.isBlank())
                .map(key -> new Resolved(row.getProvider(), key, modelFor(tenantId, userId, row)));
    }

    /**
     * The model this person's turn runs on: their own choice, else the workspace's, else the
     * provider's default. The key is the workspace's either way — only the model is theirs.
     */
    private String modelFor(String tenantId, String userId, AiProvider row) {
        if (userId != null) {
            AiUserPreference mine = preferences.findByTenantIdAndUserId(tenantId, userId).orElse(null);
            // Only honour a choice made for the provider currently connected. A workspace that
            // switches from Groq to Anthropic keeps every member's stored model, and
            // "llama-3.3-70b-versatile" sent to Anthropic fails every turn for that person while
            // the owner sees their own turns work fine — a support case nobody would guess at.
            if (mine != null && mine.getModel() != null && !mine.getModel().isBlank()
                    && java.util.Objects.equals(mine.getProvider(), row.getProvider())) {
                return mine.getModel();
            }
        }
        return effectiveModel(row);
    }

    /* ── one person's model choice ────────────────────────────────────────── */

    /** What this person's assistant runs on, and what else they could pick. */
    @Transactional(readOnly = true)
    public Map<String, Object> preference(String tenantId, String userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable).orElse(null);
        out.put("connected", row != null);
        out.put("provider", row == null ? null : row.getProvider());
        // The workspace's setting, shown as the "follow the workspace" option's label.
        out.put("workspaceModel", row == null ? null : effectiveModel(row));
        // Only surface a choice that still applies to the connected provider, so the picker shows
        // what a turn would ACTUALLY run on rather than a stale name from a previous provider.
        AiUserPreference mine = preferences.findByTenantIdAndUserId(tenantId, userId).orElse(null);
        boolean mineApplies = mine != null && mine.getModel() != null && row != null
                && java.util.Objects.equals(mine.getProvider(), row.getProvider());
        out.put("model", mineApplies ? mine.getModel() : null);
        out.put("effectiveModel", row == null ? null : modelFor(tenantId, userId, row));
        return out;
    }

    /**
     * Choose the model this person's turns run on. Blank means "follow the workspace".
     *
     * <p>Validated against the provider's live model list, not merely bounded: this string is now
     * chosen by any member and travels to the provider on every turn, so an unchecked value is a
     * member deciding what we send upstream. When the list can't be read we fall back to a length
     * bound rather than refusing — a provider outage should not stop someone changing a setting.
     */
    // NOT @Transactional: this validates against the provider, and a JPA transaction opened around
    // that pins a pooled JDBC connection for the whole round trip — the hazard connect() warns
    // about, reached here by a path any MEMBER can call. The save below is its own transaction.
    public Map<String, Object> chooseMyModel(String tenantId, String userId, String model) {
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Connect an AI provider to use the assistant."));
        String chosen = model == null ? "" : model.trim();
        if (chosen.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That model name is too long.");
        }
        if (!chosen.isEmpty()) {
            // The character check is NOT redundant with the list check below. When the provider
            // can't be read the list is empty and every model passes, and this value goes onto an
            // outbound header on every turn — where a stray character makes HttpRequest.Builder
            // throw, failing the turn and putting the offending value in the log.
            if (!headerSafe(chosen)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That doesn't look like a model name.");
            }
            List<String> available = availableModels(tenantId, row);
            if (!available.isEmpty() && !available.contains(chosen)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Your workspace's AI account can't use \"" + chosen + "\".");
            }
        }
        saveMyModel(tenantId, userId, row.getProvider(), chosen.isEmpty() ? null : chosen);
        return preference(tenantId, userId);
    }

    /**
     * The database half of {@link #chooseMyModel}.
     *
     * <p>No {@code @Transactional}: it is called from within this bean, so the annotation would
     * never reach the proxy and would mislead the next reader into thinking the read and the
     * write were atomic. They are not, and need not be — the only racer for one person's own
     * preference row is that same person in another tab, where last-write-wins is the honest
     * answer. The repository save is transactional in its own right.
     */
    void saveMyModel(String tenantId, String userId, String provider, String model) {
        AiUserPreference pref = preferences.findByTenantIdAndUserId(tenantId, userId)
                .orElseGet(() -> new AiUserPreference(java.util.UUID.randomUUID().toString(), tenantId, userId));
        pref.setModel(model);
        // Stamped with the provider it was chosen for — see modelFor().
        pref.setProvider(provider);
        preferences.save(pref);
    }

    /**
     * Whether a model name can go on an outbound header: visible ASCII only.
     *
     * <p>Same reason as {@link AiProviderProbe}'s check on the key — {@code
     * HttpRequest.Builder.header()} validates the value and throws an exception that quotes it in
     * full. A model name is member-supplied, so without this a member can fail every one of their
     * own turns and write their chosen string into our logs.
     */
    private static boolean headerSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;
        }
        return true;
    }

    /**
     * Models the workspace's key may use, empty when the provider can't be read right now.
     *
     * <p><b>Memoised, and deliberately.</b> Reading this list is an authenticated HTTP call to the
     * provider on the request thread. While it was owner-only that cost was bounded by one trusted
     * principal per workspace; it is now reachable by every MEMBER, so without a cache any member
     * could pin Tomcat workers and hammer the workspace's provider account — the same property
     * {@link AgentProxyController} rations for agent turns. The list changes when a provider ships
     * a model, not per request, so a short TTL costs nothing real.
     *
     * <p>Single-flight: on a cold miss the first caller probes and everyone else gets the last
     * known list (or an empty one, which every caller already handles) rather than queueing behind
     * it. One request per tenant per TTL reaches the provider, however many members are looking.
     */
    private List<String> availableModels(String tenantId, AiProvider row) {
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(row.getProvider()).orElse(null);
        if (provider == null) return List.of();

        String cacheKey = tenantId + "\u0000" + provider.id();
        CachedModels hit = modelCache.get(cacheKey);
        if (hit != null && !hit.isStale()) return hit.models();

        Lock lock = modelLocks.computeIfAbsent(cacheKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            // Someone else is already asking. Serve what we have rather than hold this thread.
            return hit == null ? List.of() : hit.models();
        }
        try {
            CachedModels current = modelCache.get(cacheKey);
            if (current != null && !current.isStale()) return current.models();
            List<String> models = secrets.get(tenantId, AiProvider.SECRET_NAME)
                    .filter(k -> !k.isBlank())
                    .map(k -> probe.verify(provider, k).models())
                    .orElse(List.of());
            // An empty result is cached too: a provider that is down should be asked once a TTL,
            // not once per member request.
            modelCache.put(cacheKey, new CachedModels(models, System.nanoTime()));
            if (modelCache.size() > 10_000) {
                modelCache.values().removeIf(CachedModels::isStale);
            }
            return models;
        } finally {
            lock.unlock();
        }
    }

    /** Drop a workspace's cached list — its key or provider just changed. */
    private void forgetCachedModels(String tenantId) {
        modelCache.keySet().removeIf(k -> k.startsWith(tenantId + "\u0000"));
    }

    /** Models any member may choose from — the same list, without needing the owner's rights. */
    public List<String> modelsForMembers(String tenantId) {
        AiProvider row = repository.findById(tenantId).filter(AiProvider::usable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Connect an AI provider to use the assistant."));
        return availableModels(tenantId, row);
    }

    /* ── connecting ───────────────────────────────────────────────────────── */

    /**
     * Store and start using a workspace's provider key.
     *
     * <p>Verified before it is stored: an unusable key must never become the workspace's
     * configured state, because the next thing anyone does is try to build a form with it.
     */
    // NOT @Transactional: this calls the provider, and a JPA transaction opened around that
    // pins a pooled JDBC connection for the whole round trip (up to 30s against a pool of 8) —
    // a handful of concurrent connects would starve every other query in the engine. The writes
    // below commit individually; the ordering comment at the secret write is what keeps the
    // partial states harmless.
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
        // NOTE: the key's prefix is NOT checked here any more. A prefix is a guess about a format
        // the provider owns, and the guess blocked a real Google key that did not start with
        // "AIza" — refusing on a heuristic while the definitive check sits on the next line. Ask
        // the provider; use the prefix only to explain a refusal (see below).
        AiProviderProbe.Result result = probe.verify(provider, key);
        if (result.outcome() == AiProviderProbe.Outcome.INVALID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, withKeyHint(provider, key, result.message()));
        }
        if (result.outcome() == AiProviderProbe.Outcome.REFUSED) {
            // The key authenticated and the provider refused the request — the wrong kind of key,
            // a plan without the endpoint, a region restriction. Waiting will not fix it, so this
            // must not be a 503: repeat what the provider said, since they know why and we don't.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.message());
        }
        if (result.outcome() == AiProviderProbe.Outcome.UNREACHABLE) {
            // Storing an unverified key would leave the workspace looking connected while
            // every turn fails. 503 says "try again", which is what we mean.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.message());
        }

        String chosen = normalizeModel(model, result.models(), provider);

        // Secret first, and deliberately in its own transaction: if this fails we have told
        // nobody they are connected, and if the row save below fails the stored secret is inert
        // (resolve() needs a usable row) and is overwritten by the next connect.
        secrets.put(tenantId, AiProvider.SECRET_NAME, key, ManagedBy.TENANT);

        AiProvider row = repository.findById(tenantId).orElseGet(() -> new AiProvider(tenantId));
        // A rotation is a NEW key REPLACING a live one. Three things must hold, and each has
        // been wrong at some point: there must be an existing key (a fresh row defaults to
        // status CONNECTED with no fingerprint, so the status alone says nothing), it must
        // still be live, and the incoming key must actually differ.
        boolean rotation = row.getKeyFingerprint() != null
                && AiProvider.CONNECTED.equals(row.getStatus())
                && !fingerprint(key).equals(row.getKeyFingerprint());
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
        forgetCachedModels(tenantId);  // new key or new provider — the old list is not theirs

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
    // NOT @Transactional: calls the provider — see connect().
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
    // NOT @Transactional: calls the provider — see connect().
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
    public void markInvalid(String tenantId, String message, String keyUsed) {
        repository.findById(tenantId).ifPresent(row -> {
            if (!AiProvider.CONNECTED.equals(row.getStatus())) return;
            // Only the key that actually failed may be marked. A turn can take a minute and a
            // half; if the workspace reconnected with a good key meanwhile, the late failure
            // of the OLD key would otherwise switch off the NEW one — and the more urgently
            // someone fixes a bad key, the more likely they hit exactly that window.
            if (keyUsed != null && !fingerprint(keyUsed).equals(row.getKeyFingerprint())) {
                log.debug("ai: ignoring a rejection for tenant {} — the key has changed since", tenantId);
                return;
            }
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
        preferences.deleteByTenant(tenantId);
        repository.deleteById(tenantId);
    }

    /** Called when a user is deprovisioned: their model choices go with them. */
    @Transactional
    public void forgetUser(String userId) {
        preferences.deleteByUser(userId);
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

    /**
     * Add "that looks like an X key" when a refused key matches a different provider's format.
     *
     * <p>This is what the prefix check is actually good for: not deciding whether to try, but
     * explaining a refusal once the provider has spoken. The common case is two keys sitting
     * beside each other in a password manager.
     */
    private String withKeyHint(AiProviderCatalog.Provider provider, String key, String message) {
        return AiProviderCatalog.looksLikeKeyOf(key)
                .filter(other -> !other.id().equals(provider.id()))
                .map(other -> message + " That looks like " + article(other.label()) + " "
                        + other.label() + " key — pick " + other.label() + " above if it is.")
                .orElse(message);
    }

    private static String article(String label) {
        return "AEIOU".indexOf(Character.toUpperCase(label.charAt(0))) >= 0 ? "an" : "a";
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
