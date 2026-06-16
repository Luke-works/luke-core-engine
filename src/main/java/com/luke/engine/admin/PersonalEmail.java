package com.luke.engine.admin;

import java.util.Locale;
import java.util.Set;

/**
 * Detects personal / free mailbox providers (gmail, yahoo, …). Used to keep the
 * company-sending EMAIL capability off accounts that signed up with a personal
 * address — they can't verify a business sending domain.
 *
 * <p>Mirrors {@code OrgDomainMatcher.FREE_PROVIDERS} in luke-capability-engine and
 * {@code emailDomains.ts} in luke-consumer-ui; keep the three lists in sync.
 */
public final class PersonalEmail {

    private static final Set<String> DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.in",
            "outlook.com", "hotmail.com", "hotmail.co.uk", "live.com", "msn.com",
            "icloud.com", "me.com", "mac.com", "aol.com", "proton.me", "protonmail.com",
            "pm.me", "gmx.com", "gmx.net", "mail.com", "yandex.com", "zoho.com",
            "hey.com", "fastmail.com", "tutanota.com", "qq.com", "163.com", "126.com");

    private PersonalEmail() {}

    /** True when the email is on a personal/free provider. Null/blank → false (don't restrict). */
    public static boolean isPersonal(String email) {
        if (email == null) return false;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return false;
        return DOMAINS.contains(email.substring(at + 1).trim().toLowerCase(Locale.ROOT));
    }
}
