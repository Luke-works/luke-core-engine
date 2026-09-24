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

    private record CachedModels(List<AiProviderProbe.ModelInfo> models, long readAt) {
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
     * What the settings page shows: every provider this workspace has connected, with the
     * status of each, plus the catalog of ones it could add.
     *
     * <p>Contains no key — {@code keyLast4} is the only fragment, so a human can tell which of
     * their keys is which.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<AiProvider> rows = repository.findByTenantIdOrderByProviderAsc(tenantId);
        List<Map<String, Object>> connected = rows.stream()
                .filter(r -> !AiProvider.DISCONNECTED.equals(r.getStatus()))
                .map(this::describeConnection)
                .toList();

        out.put("connections", connected);
        // True when at least one can actually run a turn — what every AI panel gates on.
        out.put("connected", rows.stream().anyMatch(AiProvider::usable));
        out.put("providers", AiProviderCatalog.all().stream().map(p -> Map.of(
                "id", p.id(), "label", p.label(), "defaultModel", p.defaultModel(),
                // Shown as the key field's placeholder, so a wrong-provider paste is obvious
                // before it is submitted. NEVER used to refuse a key — see AiProviderCatalog.
                "keyPrefix", p.keyPrefix(), "consoleUrl", p.consoleUrl(),
                // So the page can offer "Add" for what is missing and "Replace key" for what is not.
                "connected", rows.stream().anyMatch(r -> r.getProvider().equals(p.id()) && r.usable()))).toList());
        return out;
    }

    /** One connected provider, as a human may see it. Never the key. */
    private Map<String, Object> describeConnection(AiProvider row) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("provider", row.getProvider());
        AiProviderCatalog.find(row.getProvider()).ifPresent(p -> c.put("label", p.label()));
        c.put("status", row.getStatus());
        c.put("preferred", row.isPreferred());
        c.put("model", row.getModel());
        // Shown whether or not they picked one, so "no model chosen" never reads as "no model".
        c.put("effectiveModel", effectiveModel(row));
        c.put("keyLast4", row.getKeyLast4());
        c.put("connectedAt", row.getConnectedAt());
        c.put("connectedBy", row.getConnectedBy());
        c.put("verifiedAt", row.getVerifiedAt());
        c.put("lastError", row.getLastError());
        return c;
    }

    private String effectiveModel(AiProvider row) {
        if (row.getModel() != null && !row.getModel().isBlank()) return row.getModel();
        return AiProviderCatalog.find(row.getProvider())
                .map(AiProviderCatalog.Provider::defaultModel).orElse(null);
    }

    /** The provider a turn uses when the person running it has expressed no preference. */
    private Optional<AiProvider> preferredProvider(String tenantId) {
        List<AiProvider> usable = repository.findByTenantIdOrderByProviderAsc(tenantId).stream()
                .filter(AiProvider::usable).toList();
        // The flag first; falling back to the only/first usable one keeps a turn running even if
        // the flag were ever lost, rather than failing over bookkeeping.
        return usable.stream().filter(AiProvider::isPreferred).findFirst()
                .or(() -> usable.stream().findFirst());
    }

    /**
     * The credential to attach to an agent turn, or empty when this workspace cannot run one.
     *
     * <p>Which provider depends on the person: their own choice names one, and otherwise the
     * workspace's preferred one is used. Empty covers every "no" — nothing connected, all
     * disconnected or invalid, or the row exists but its secret has gone. The caller turns that
     * into the single 402 the UI knows how to render; it must not need to distinguish them.
     */
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String tenantId, String userId) {
        AiUserPreference mine = userId == null ? null
                : preferences.findByTenantIdAndUserId(tenantId, userId).orElse(null);

        AiProvider row = null;
        if (mine != null && mine.getProvider() != null) {
            // Only if that provider is still connected and usable — a workspace can remove one
            // out from under a member who had chosen it.
            row = repository.findByTenantIdAndProvider(tenantId, mine.getProvider())
                    .filter(AiProvider::usable).orElse(null);
        }
        if (row == null) row = preferredProvider(tenantId).orElse(null);
        if (row == null) return Optional.empty();

        AiProvider chosen = row;
        String model = (mine != null && mine.getModel() != null && !mine.getModel().isBlank()
                && chosen.getProvider().equals(mine.getProvider()))
                ? mine.getModel()
                : effectiveModel(chosen);
        return secrets.get(tenantId, chosen.secretName())
                .filter(key -> !key.isBlank())
                .map(key -> new Resolved(chosen.getProvider(), key, model));
    }

    /* ── one person's model choice ────────────────────────────────────────── */

    /** What this person's assistant runs on, and what else they could pick. */
    @Transactional(readOnly = true)
    public Map<String, Object> preference(String tenantId, String userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        AiProvider fallback = preferredProvider(tenantId).orElse(null);
        AiUserPreference mine = preferences.findByTenantIdAndUserId(tenantId, userId).orElse(null);

        // Only surface a choice whose provider is STILL connected, so the picker shows what a
        // turn would actually run on rather than a name from a provider since removed.
        boolean mineApplies = mine != null && mine.getModel() != null && mine.getProvider() != null
                && repository.findByTenantIdAndProvider(tenantId, mine.getProvider())
                        .filter(AiProvider::usable).isPresent();

        out.put("connected", fallback != null);
        out.put("provider", mineApplies ? mine.getProvider() : (fallback == null ? null : fallback.getProvider()));
        out.put("model", mineApplies ? mine.getModel() : null);
        // The workspace's own setting, offered as the "follow the workspace" option's label.
        out.put("workspaceProvider", fallback == null ? null : fallback.getProvider());
        out.put("workspaceModel", fallback == null ? null : effectiveModel(fallback));
        out.put("effectiveModel", mineApplies ? mine.getModel()
                : (fallback == null ? null : effectiveModel(fallback)));
        // Labels for the providers this workspace actually has, so the picker can head each
        // group with "Anthropic" rather than "anthropic" without hardcoding a name table.
        out.put("providers", repository.findByTenantIdOrderByProviderAsc(tenantId).stream()
                .filter(AiProvider::usable)
                .map(r -> Map.of("id", r.getProvider(), "label", label(r.getProvider())))
                .toList());
        return out;
    }

    /**
     * Choose the provider and model this person's turns run on. Blank means "follow the workspace".
     *
     * <p>Validated against that provider's live model list, not merely bounded: this string is
     * chosen by any member and travels to the provider on every turn, so an unchecked value is a
     * member deciding what we send upstream. When the list can't be read we fall back to a length
     * bound rather than refusing — a provider outage should not stop someone changing a setting.
     */
    // NOT @Transactional: this validates against the provider, and a JPA transaction opened around
    // that pins a pooled JDBC connection for the whole round trip — the hazard connect() warns
    // about, reached here by a path any MEMBER can call. The save below is its own transaction.
    public Map<String, Object> chooseMyModel(String tenantId, String userId, String providerId, String model) {
        String chosen = model == null ? "" : model.trim();
        if (chosen.isEmpty()) {
            // Back to following the workspace: forget the provider too, or a later reconnect of
            // that provider would silently resurrect an old choice.
            saveMyModel(tenantId, userId, null, null);
            return preference(tenantId, userId);
        }
        if (chosen.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That model name is too long.");
        }
        // The character check is NOT redundant with the list check below. When the provider can't
        // be read the list is empty and every model passes, and this value goes onto an outbound
        // header on every turn — where a stray character makes HttpRequest.Builder throw, failing
        // the turn and putting the offending value in the log.
        if (!headerSafe(chosen)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That doesn't look like a model name.");
        }

        AiProvider row = usableProvider(tenantId, providerId);
        List<String> available = availableModels(tenantId, row).stream()
                .map(AiProviderProbe.ModelInfo::id).toList();
        if (!available.isEmpty() && !available.contains(chosen)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your workspace's " + label(row.getProvider()) + " account can't use \"" + chosen + "\".");
        }
        saveMyModel(tenantId, userId, row.getProvider(), chosen);
        return preference(tenantId, userId);
    }

    /**
     * The row for one connected provider, or the workspace's preferred one when none is named.
     *
     * <p>402 rather than 404 throughout: to the UI "this workspace cannot run a turn" is one
     * state with one remedy, whoever caused it.
     */
    private AiProvider usableProvider(String tenantId, String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return preferredProvider(tenantId).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED, "Connect an AI provider to use the assistant."));
        }
        return repository.findByTenantIdAndProvider(tenantId, providerId.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(AiProvider::usable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "That AI provider isn't connected for this workspace."));
    }

    private static String label(String providerId) {
        return AiProviderCatalog.find(providerId).map(AiProviderCatalog.Provider::label).orElse(providerId);
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
     * Models one provider's key may use, empty when it can't be read right now.
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
     * it. One request per provider per TTL reaches it, however many members are looking.
     */
    private List<AiProviderProbe.ModelInfo> availableModels(String tenantId, AiProvider row) {
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
            List<AiProviderProbe.ModelInfo> models = secrets.get(tenantId, row.secretName())
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

    /** One model a workspace could pick, and which of its providers offers it. */
    public record OfferedModel(String provider, String id, boolean chat) {}

    /**
     * The wire shape for a model list: which provider offers it, its id, and whether an agent
     * turn could run on it.
     *
     * <p>A provider lists every model the account can reach across all modalities. Offering
     * Whisper or an embedding model as an equal choice is a trap — pick one and every turn fails
     * — so the capability travels with the id and the UI groups on it. Nothing is hidden: the
     * classification is partly a guess about names the provider owns, and a wrong guess should
     * demote a model, never make it unreachable.
     */
    public static List<Map<String, Object>> describe(List<OfferedModel> models) {
        return models.stream()
                .map(m -> Map.<String, Object>of("provider", m.provider(), "id", m.id(), "chat", m.chat()))
                .toList();
    }

    /**
     * Everything this workspace could pick, across EVERY connected provider.
     *
     * <p>The point of connecting more than one: a person can run on the cheap fast provider for
     * a quick draft and the capable one for something hard, without an owner switching anything.
     * Readable by any member — everyone picks their own, and it carries model names, never a key.
     */
    public List<OfferedModel> modelsForMembers(String tenantId) {
        List<AiProvider> usable = repository.findByTenantIdOrderByProviderAsc(tenantId).stream()
                .filter(AiProvider::usable).toList();
        if (usable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Connect an AI provider to use the assistant.");
        }
        List<OfferedModel> out = new java.util.ArrayList<>();
        for (AiProvider row : usable) {
            // One provider being unreadable must not hide the others: availableModels already
            // degrades to an empty list rather than throwing.
            for (AiProviderProbe.ModelInfo m : availableModels(tenantId, row)) {
                out.add(new OfferedModel(row.getProvider(), m.id(), m.chat()));
            }
        }
        return List.copyOf(out);
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

        String chosen = normalizeModel(model, result.modelIds(), provider);

        // Under THIS provider's own name. One shared name was what made connecting a second
        // provider silently destroy the first one's key.
        secrets.put(tenantId, AiProvider.secretName(provider.id()), key, ManagedBy.TENANT);

        AiProvider row = repository.findByTenantIdAndProvider(tenantId, provider.id())
                .orElseGet(() -> new AiProvider(tenantId, provider.id()));
        // A rotation is a NEW key REPLACING a live one. Three things must hold, and each has
        // been wrong at some point: there must be an existing key (a fresh row defaults to
        // status CONNECTED with no fingerprint, so the status alone says nothing), it must
        // still be live, and the incoming key must actually differ.
        boolean rotation = row.getKeyFingerprint() != null
                && AiProvider.CONNECTED.equals(row.getStatus())
                && !fingerprint(key).equals(row.getKeyFingerprint());
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
        forgetCachedModels(tenantId);  // a new key for this provider — the old list is not its

        // The first provider a workspace connects is the one turns default to. Later ones join
        // beside it without stealing that, unless the workspace says so explicitly.
        if (repository.findByTenantIdOrderByProviderAsc(tenantId).stream()
                .filter(AiProvider::usable).noneMatch(AiProvider::isPreferred)) {
            row.setPreferred(true);
            repository.save(row);
        }

        audit.record(rotation ? "ai.provider.updated" : "ai.provider.connected", "ai_provider", tenantId,
                tenantId, userId, false, Map.of("provider", provider.id(), "model", String.valueOf(chosen)));
        log.info("ai: tenant {} connected {} (model={})", tenantId, provider.id(), chosen);

        Map<String, Object> out = new LinkedHashMap<>(view(tenantId));
        out.put("models", describe(result.models().stream()
                .map(m -> new OfferedModel(provider.id(), m.id(), m.chat())).toList()));
        return out;
    }

    /** Change the model without re-pasting the key. */
    @Transactional
    public Map<String, Object> chooseModel(String tenantId, String userId, String providerId, String model) {
        AiProvider row = usableProvider(tenantId, providerId);
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(row.getProvider())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Unknown provider on this workspace."));
        row.setModel(normalizeModel(model, List.of(), provider));
        repository.save(row);
        audit.record("ai.provider.model-changed", "ai_provider", tenantId, tenantId, userId, false,
                Map.of("provider", row.getProvider(), "model", String.valueOf(row.getModel())));
        return view(tenantId);
    }

    /**
     * Re-check the stored key against the provider and record what we learn.
     *
     * <p>Used by the connect page and after a turn fails. An UNREACHABLE outcome changes
     * nothing — see {@link #markInvalid}.
     */
    // NOT @Transactional: calls the provider — see connect().
    public Map<String, Object> verify(String tenantId, String providerId) {
        AiProvider row = repository.findByTenantIdAndProvider(tenantId, providerId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That provider isn't connected for this workspace."));
        AiProviderCatalog.Provider provider = AiProviderCatalog.find(row.getProvider())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Unknown provider on this workspace."));
        String key = secrets.get(tenantId, row.secretName()).orElse(null);
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
    public void markInvalid(String tenantId, String providerId, String message, String keyUsed) {
        // Only the provider whose key was actually used. A workspace may have several connected,
        // and one refusing says nothing about the others.
        repository.findByTenantIdAndProvider(tenantId, providerId).ifPresent(row -> {
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
    public Map<String, Object> disconnect(String tenantId, String userId, String providerId) {
        AiProvider row = repository.findByTenantIdAndProvider(tenantId, providerId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That provider isn't connected for this workspace."));
        secrets.delete(tenantId, row.secretName());
        row.setStatus(AiProvider.DISCONNECTED);
        row.setDisconnectedAt(LocalDateTime.now());
        row.setKeyFingerprint(null);
        row.setKeyLast4(null);
        row.setLastError(null);
        boolean wasPreferred = row.isPreferred();
        row.setPreferred(false);
        repository.save(row);
        forgetCachedModels(tenantId);

        // Removing the default must not leave the workspace with none: hand it to whatever is
        // still usable, or turns would start failing for people who never chose a provider.
        if (wasPreferred) {
            repository.findByTenantIdOrderByProviderAsc(tenantId).stream()
                    .filter(AiProvider::usable).findFirst()
                    .ifPresent(next -> {
                        next.setPreferred(true);
                        repository.save(next);
                    });
        }
        audit.record("ai.provider.disconnected", "ai_provider", tenantId, tenantId, userId, false,
                Map.of("provider", String.valueOf(row.getProvider())));
        return view(tenantId);
    }

    /** Choose which connected provider a turn uses when the person running it hasn't picked one. */
    @Transactional
    public Map<String, Object> setPreferred(String tenantId, String userId, String providerId) {
        AiProvider chosen = repository.findByTenantIdAndProvider(tenantId, providerId)
                .filter(AiProvider::usable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That provider isn't connected for this workspace."));
        for (AiProvider row : repository.findByTenantIdOrderByProviderAsc(tenantId)) {
            // Exactly one, always: two would make "which provider runs this turn" depend on row
            // order, and none would strand everyone who never picked one.
            boolean shouldBe = row.getId().equals(chosen.getId());
            if (row.isPreferred() != shouldBe) {
                row.setPreferred(shouldBe);
                repository.save(row);
            }
        }
        audit.record("ai.provider.default-changed", "ai_provider", tenantId, tenantId, userId, false,
                Map.of("provider", providerId));
        return view(tenantId);
    }

    /** Called when a tenant is purged: the key must not outlive the workspace. */
    @Transactional
    public void forget(String tenantId) {
        // Every provider's key, not just one: a workspace may have connected several.
        for (AiProvider row : repository.findByTenantIdOrderByProviderAsc(tenantId)) {
            secrets.delete(tenantId, row.secretName());
        }
        preferences.deleteByTenant(tenantId);
        repository.deleteByTenant(tenantId);
        forgetCachedModels(tenantId);
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
