package com.luke.engine.recipient;

import java.util.List;
import java.util.Optional;

/**
 * SPI a capability implements to contribute items to the recipient PORTAL. The portal is the recipient
 * persona's cross-capability hub: it owns identity (account-less email/SMS-OTP + magic-link sessions)
 * and aggregates every open item for {@code (tenant, email)} across all providers. Forms is the first
 * provider; signatures, documents, … plug in by implementing this SPI + a frontend renderer for their
 * {@link #type()} — no change to the portal core.
 *
 * <p>The portal session ({@code PortalAccessTokens}) is capability-agnostic; each capability still owns
 * and authorises its own open/fill/act surface, validating the session against the item's recipient.
 */
public interface RecipientItemProvider {

    /** Stable discriminator the frontend renders on (e.g. {@code "form"}). Unique per provider. */
    String type();

    /** Every open item assigned to this recipient email in this tenant, newest first. */
    List<RecipientItem> itemsFor(String tenantId, String email);

    /**
     * A phone number on file for this recipient, if this capability holds one — used only for the SMS
     * OTP channel. Default: none. The portal takes the first phone any provider returns.
     */
    default Optional<String> phoneFor(String tenantId, String email) {
        return Optional.empty();
    }
}
