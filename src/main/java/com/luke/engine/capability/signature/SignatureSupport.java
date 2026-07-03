package com.luke.engine.capability.signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Stateless helpers for signature codes, public sign tokens, and the lifecycle string
 * constants. Mirrors {@code FormSupport} in luke-core-engine (SecureRandom, SR- prefix).
 */
public final class SignatureSupport {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private SignatureSupport() {}

    /** SignatureRequest lifecycle states (stored on {@code SignatureRequest.status}). */
    public static final class Status {
        public static final String DRAFT = "DRAFT";
        public static final String SENT = "SENT";
        public static final String VIEWED = "VIEWED";
        public static final String SIGNED = "SIGNED";
        public static final String COMPLETED = "COMPLETED";
        public static final String VOIDED = "VOIDED";
        private Status() {}
    }

    /** Audit actions (stored on {@code SignatureAuditEvent.action}). */
    public static final class Action {
        public static final String CREATED = "CREATED";
        public static final String SENT = "SENT";
        public static final String VIEWED = "VIEWED";
        public static final String SIGNED = "SIGNED";
        public static final String DOWNLOADED = "DOWNLOADED";
        public static final String VOIDED = "VOIDED";
        public static final String PURGED = "PURGED";
        /** A blocked attempt (IP-risk policy) — recorded so the trail never shows a phantom SIGNED. */
        public static final String BLOCKED = "BLOCKED";
        private Action() {}
    }

    /** Human signature code, e.g. "SR-XKQW-19JUN26" (all caps, current date). */
    public static String generateCode() {
        return generateCode(LocalDate.now());
    }

    public static String generateCode(LocalDate date) {
        return code("SR-", date);
    }

    /** Human signature DEFINITION code, e.g. "SD-XKQW-27JUN26" (design-time, all caps). */
    public static String generateDefinitionCode() {
        return code("SD-", LocalDate.now());
    }

    public static String generateDefinitionCode(LocalDate date) {
        return code("SD-", date);
    }

    private static String code(String prefix, LocalDate date) {
        StringBuilder letters = new StringBuilder(4);
        for (int i = 0; i < 4; i++) letters.append(LETTERS.charAt(RNG.nextInt(LETTERS.length())));
        String month = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
        String dd = String.format("%02d", date.getDayOfMonth());
        String yy = String.format("%02d", date.getYear() % 100);
        return prefix + letters + "-" + dd + month + yy;
    }

    /** Unguessable 32-char URL-safe public sign token (~190 bits of entropy). */
    public static String generateSignToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(TOKEN_ALPHABET.charAt(RNG.nextInt(TOKEN_ALPHABET.length())));
        return sb.toString();
    }

    /** Lowercase hex SHA-256 of the given bytes (document integrity hash). */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** LocalDateTime → epoch millis (ms-adapter-friendly for the UI), null-safe. */
    public static Long epochMillis(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
