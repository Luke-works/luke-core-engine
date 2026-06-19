package com.luke.engine.capability.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luke.engine.admin.PersonalEmail;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #38: one source of truth for the free-provider list (matcher → PersonalEmail → endpoint). */
class PublicEmailMetaTest {

    @Test
    void personalEmailDelegatesToTheMatcher() {
        assertTrue(PersonalEmail.isPersonal("jo@gmail.com"));
        assertFalse(PersonalEmail.isPersonal("jo@acme.com"));
        assertFalse(PersonalEmail.isPersonal("jo@"));   // no domain
        assertFalse(PersonalEmail.isPersonal(null));
        // delegation, not a private copy:
        assertEquals(OrgDomainMatcher.isFreeProvider("gmail.com"), PersonalEmail.isPersonal("x@gmail.com"));
    }

    @Test
    void endpointServesTheCanonicalListSorted() {
        List<String> providers = new PublicEmailMetaController()
                .freeEmailDomains().getBody().get("providers");

        assertTrue(providers.contains("gmail.com"));
        assertEquals(OrgDomainMatcher.freeProviders().size(), providers.size());
        assertEquals(providers.stream().sorted().toList(), providers);  // sorted
    }
}
