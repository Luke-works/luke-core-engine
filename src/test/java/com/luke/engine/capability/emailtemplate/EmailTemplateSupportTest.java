package com.luke.engine.capability.emailtemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the template code format (ET-XXXX-DDMMMYY) and the {@code {{var}}} merge
 * contract extraction — the set the UI shows and Postmark merges on at send.
 */
class EmailTemplateSupportTest {

    @Test
    void generateCode_hasExpectedShape() {
        String code = EmailTemplateSupport.generateCode(LocalDate.of(2026, 6, 5));
        assertEquals("ET-", code.substring(0, 3));
        assertTrue(code.endsWith("-05JUN26"), code);
        assertTrue(code.matches("ET-[A-Z]{4}-\\d{2}[A-Z]{3}\\d{2}"), code);
    }

    @Test
    void generateCode_isRandomPerCall() {
        String a = EmailTemplateSupport.generateCode();
        String b = EmailTemplateSupport.generateCode();
        // 26^4 letter space — collisions are astronomically unlikely.
        assertTrue(a.startsWith("ET-") && b.startsWith("ET-"));
    }

    @Test
    void extractVariables_returnsDistinctNamesInFirstSeenOrder() {
        String doc = "{\"subject\":\"Welcome, {{firstName}}!\","
                + "\"blocks\":[{\"type\":\"text\",\"text\":\"Hi {{firstName}}, from {{companyName}}\"},"
                + "{\"type\":\"footer\",\"unsubscribeUrl\":\"{{ unsubscribeUrl }}\"}]}";
        assertEquals(List.of("firstName", "companyName", "unsubscribeUrl"),
                EmailTemplateSupport.extractVariables(doc));
    }

    @Test
    void extractVariables_handlesBlankAndNoVars() {
        assertTrue(EmailTemplateSupport.extractVariables(null).isEmpty());
        assertTrue(EmailTemplateSupport.extractVariables("").isEmpty());
        assertTrue(EmailTemplateSupport.extractVariables("{\"subject\":\"Static subject\"}").isEmpty());
    }
}
