package com.luke.engine.capability.minion;

import java.util.Map;

/**
 * A "minion" — a named, server-side operation a form field can invoke through the secure minion proxy
 * (e.g. an option data source, an async validation, or a geocoding PROVIDER for address autocomplete).
 *
 * <p>The crucial property: ALL credentials and authorization live HERE (server-side). The browser only
 * names an operation and passes parameters; it never holds a provider token or a URL secret. A minion
 * implementation must therefore NOT trust {@code params} for authorization — the tenant is resolved by
 * the controller (session for the authed endpoint, embed token for the public one) and handed in.
 *
 * <p>Register a minion by declaring it a Spring {@code @Component}; {@link MinionRegistry} collects all
 * of them by {@link #name()}.
 */
public interface Minion {

    /** The operation name the form references (e.g. {@code "geocode"}). Must be unique. */
    String name();

    /**
     * Whether this minion may be called from the UNauthenticated public/embed endpoint (token-scoped).
     * Defaults to {@code false} — a minion is internal-only unless it explicitly opts in, so public
     * embeds can only reach operations vetted as safe to expose.
     */
    default boolean publicAllowed() {
        return false;
    }

    /**
     * Handle a call. {@code tenantId} is the resolved tenant (never from the browser); {@code params}
     * is the request body. Returns any JSON-serializable value (the controller serializes it as-is).
     */
    Object handle(String tenantId, Map<String, Object> params);
}
