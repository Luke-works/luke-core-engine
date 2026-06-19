package com.luke.engine.capability.emailtemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless helpers for email-template codes and deriving the {@code {{var}}} merge
 * contract from a stored EmailDoc JSON. Mirrors
 * {@link com.luke.engine.capability.form.FormSupport}.
 */
public final class EmailTemplateSupport {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** A "variable" is any {@code {{identifier}}} occurrence (Postmark/Mustachio merge key). */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");

    private EmailTemplateSupport() {}

    /** Human template code, e.g. "ET-XKQW-05JUN26" (all caps, current date). */
    public static String generateCode() {
        return generateCode(LocalDate.now());
    }

    public static String generateCode(LocalDate date) {
        StringBuilder letters = new StringBuilder(4);
        for (int i = 0; i < 4; i++) letters.append(LETTERS.charAt(RNG.nextInt(26)));
        String month = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
        String dd = String.format("%02d", date.getDayOfMonth());
        String yy = String.format("%02d", date.getYear() % 100);
        return "ET-" + letters + "-" + dd + month + yy;
    }

    /**
     * Distinct {@code {{var}}} names appearing anywhere in the EmailDoc JSON, in
     * first-seen order. Scans the raw JSON text — placeholders live in subject and any
     * block text/label/href/src and footer.unsubscribeUrl, so a textual scan over the
     * whole doc captures them all. Best-effort: a blank doc yields no contract.
     */
    public static List<String> extractVariables(String docJson) {
        List<String> vars = new ArrayList<>();
        if (docJson == null || docJson.isBlank()) return vars;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = VAR.matcher(docJson);
        while (m.find()) seen.add(m.group(1));
        vars.addAll(seen);
        return vars;
    }
}
