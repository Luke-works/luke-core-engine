package com.luke.engine.admin;

import com.luke.engine.capability.email.OrgDomainMatcher;

/**
 * Detects personal / free mailbox providers (gmail, yahoo, …). Used to keep the
 * company-sending EMAIL capability off accounts that signed up with a personal
 * address — they can't verify a business sending domain.
 *
 * <p>Delegates to {@link OrgDomainMatcher#freeProviders()} — the single source of
 * truth (#38), also served to the client at {@code /api/public/meta/free-email-domains}.
 */
public final class PersonalEmail {

    private PersonalEmail() {}

    /** True when the email is on a personal/free provider. Null/blank → false (don't restrict). */
    public static boolean isPersonal(String email) {
        return OrgDomainMatcher.isFreeProvider(OrgDomainMatcher.domainOf(email));
    }
}
