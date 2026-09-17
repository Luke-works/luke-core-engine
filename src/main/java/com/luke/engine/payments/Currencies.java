package com.luke.engine.payments;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The currencies a form may charge in — the Java copy of form-core's {@code payments/currencies.ts},
 * kept identical (the parity fixture exercises both).
 *
 * <p>{@code exponent} is the ISO-4217 minor-unit exponent and is load-bearing: get it wrong and every
 * amount in that currency is off by a factor of ten or more. {@code minimumMinor} is an advisory
 * processor floor used as the default minimum for payer-entered amounts. An unknown currency is
 * refused rather than guessed.
 */
public final class Currencies {

    /** One supported currency. */
    public record Info(String code, int exponent, long minimumMinor) {}

    private static final List<String> ZERO_DECIMAL = List.of("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW",
            "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");
    private static final List<String> THREE_DECIMAL = List.of("BHD", "JOD", "KWD", "OMR", "TND");
    private static final Map<String, Long> MINIMUMS = Map.ofEntries(
            Map.entry("USD", 50L), Map.entry("EUR", 50L), Map.entry("GBP", 30L), Map.entry("AUD", 50L),
            Map.entry("CAD", 50L), Map.entry("CHF", 50L), Map.entry("NZD", 50L), Map.entry("SGD", 50L),
            Map.entry("DKK", 250L), Map.entry("NOK", 300L), Map.entry("SEK", 300L), Map.entry("PLN", 200L),
            Map.entry("RON", 200L), Map.entry("BGN", 100L), Map.entry("CZK", 1500L), Map.entry("HUF", 17500L),
            Map.entry("HKD", 400L), Map.entry("MXN", 1000L), Map.entry("BRL", 50L), Map.entry("INR", 50L),
            Map.entry("AED", 200L), Map.entry("MYR", 200L), Map.entry("JPY", 50L));
    private static final List<String> TWO_DECIMAL_EXTRA =
            List.of("ZAR", "ILS", "TRY", "THB", "PHP", "IDR", "SAR", "QAR", "KES", "NGN");

    private static final Map<String, Info> TABLE = build();

    private Currencies() {}

    private static Map<String, Info> build() {
        Map<String, Info> m = new TreeMap<>();
        for (String c : ZERO_DECIMAL) put(m, c, 0);
        for (String c : THREE_DECIMAL) put(m, c, 3);
        for (String c : MINIMUMS.keySet()) if (!m.containsKey(c)) put(m, c, 2);
        for (String c : TWO_DECIMAL_EXTRA) if (!m.containsKey(c)) put(m, c, 2);
        return Map.copyOf(m);
    }

    private static void put(Map<String, Info> m, String code, int exponent) {
        long fallback = exponent == 3 ? 500 : 50;
        m.put(code, new Info(code, exponent, MINIMUMS.getOrDefault(code, fallback)));
    }

    /** The supported currency for a raw code (trimmed, case-insensitive), or empty. */
    public static Optional<Info> of(String raw) {
        if (raw == null) return Optional.empty();
        return Optional.ofNullable(TABLE.get(PaymentAmountResolver.jsTrim(raw).toUpperCase(Locale.ROOT)));
    }

    /** Every supported code, sorted. */
    public static List<String> codes() {
        return TABLE.keySet().stream().sorted().toList();
    }

    /** Render minor units as a major-unit string ({@code 4999 → "49.99"}), for messages and receipts. */
    public static String toMajorString(long amountMinor, Info currency) {
        return java.math.BigDecimal.valueOf(amountMinor, currency.exponent()).toPlainString();
    }
}
