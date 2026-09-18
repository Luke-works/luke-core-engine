package com.luke.engine.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The resolver's publish-time checks and the JavaScript-compatibility helpers the parity fixture
 * relies on. Amount resolution itself is covered case-by-case by {@link PaymentAmountResolverParityTest}.
 */
class PaymentAmountResolverTest {

    private static String schema(String payAttrs, String extraEntities) {
        // Every extra entity is placed at the top level too, so nothing is unreachable by accident.
        String extraIds = java.util.regex.Pattern.compile("(?:^|,)\"([A-Za-z0-9_]+)\":\\{\"id\"").matcher(extraEntities)
                .results().map(m -> ",\"" + m.group(1) + "\"").collect(java.util.stream.Collectors.joining());
        return "{\"root\":[\"pay\"" + extraIds + "],\"entities\":{\"pay\":{\"id\":\"pay\",\"type\":\"payment\",\"attributes\":{\"key\":\"pay\","
                + payAttrs + "}}" + (extraEntities.isEmpty() ? "" : "," + extraEntities) + "}}";
    }

    private static final String QTY = "\"qty\":{\"id\":\"qty\",\"type\":\"number\",\"attributes\":{\"key\":\"qty\",\"required\":true}}";

    @Test
    void aSoundPaymentHasNoProblems() {
        assertThat(PaymentAmountResolver.configProblems(schema("\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\"", ""))).isEmpty();
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"perUnit\",\"amountMinor\":500,\"currency\":\"USD\",\"quantityFrom\":\"qty\"", QTY))).isEmpty();
        assertThat(PaymentAmountResolver.configProblems("{\"root\":[],\"entities\":{}}")).isEmpty();
        assertThat(PaymentAmountResolver.configProblems(null)).isEmpty();
    }

    @Test
    void publishBlockingProblemsAreReported() {
        assertThat(PaymentAmountResolver.configProblems(schema("\"amountMode\":\"fixed\",\"amountMinor\":500", "")))
                .anyMatch(p -> p.contains("currency"));
        assertThat(PaymentAmountResolver.configProblems(schema("\"amountMode\":\"calculated\",\"currency\":\"USD\"", "")))
                .anyMatch(p -> p.contains("how the payment amount is decided"));
        assertThat(PaymentAmountResolver.configProblems(schema("\"amountMode\":\"fixed\",\"currency\":\"KWD\",\"amountMinor\":1234", "")))
                .anyMatch(p -> p.contains("can't be charged"));
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"entered\",\"currency\":\"USD\",\"amountFrom\":\"qty\"", QTY)))
                .anyMatch(p -> p.contains("maximum"));
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"perUnit\",\"amountMinor\":1000000,\"currency\":\"USD\",\"quantityFrom\":\"qty\"", QTY)))
                .anyMatch(p -> p.contains("larger than a single payment"));
        assertThat(PaymentAmountResolver.configProblems(schema("\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\",\"hidden\":true", "")))
                .anyMatch(p -> p.contains("conditional"));
        assertThat(PaymentAmountResolver.configProblems(schema("\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\",\"persistent\":false", "")))
                .containsExactly("The payment field can't be excluded from the submission.");
    }

    @Test
    void theQuantitySourceMustBeRequiredAndPlain() {
        String optionalQty = "\"qty\":{\"id\":\"qty\",\"type\":\"number\",\"attributes\":{\"key\":\"qty\"}}";
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"perUnit\",\"amountMinor\":500,\"currency\":\"USD\",\"quantityFrom\":\"qty\"", optionalQty)))
                .containsExactly("The payment quantity field \"qty\" must be required.");
        String calculated = "\"qty\":{\"id\":\"qty\",\"type\":\"number\",\"attributes\":{\"key\":\"qty\",\"required\":true,\"calculateValue\":\"a*2\"}}";
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"perUnit\",\"amountMinor\":500,\"currency\":\"USD\",\"quantityFrom\":\"qty\"", calculated)))
                .anyMatch(p -> p.contains("calculated"));
    }

    @Test
    void twoPaymentFieldsAreOneProblem() {
        String two = "{\"root\":[\"a\",\"b\"],\"entities\":{"
                + "\"a\":{\"id\":\"a\",\"type\":\"payment\",\"attributes\":{\"key\":\"a\"}},"
                + "\"b\":{\"id\":\"b\",\"type\":\"payment\",\"attributes\":{\"key\":\"b\"}}}}";
        assertThat(PaymentAmountResolver.configProblems(two)).containsExactly("The form has 2 payment fields; keep exactly one.");
        assertThat(PaymentAmountResolver.resolve(two, Map.of()).failure()).isEqualTo(PaymentAmountResolver.Failure.MULTIPLE_PAYMENT_FIELDS);
    }

    @Test
    void boundsStoredWithTheWrongTypeAreReported() {
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"perUnit\",\"amountMinor\":500,\"currency\":\"USD\",\"quantityFrom\":\"qty\",\"maxQuantity\":\"5\"", QTY)))
                .anyMatch(p -> p.contains("maximum quantity"));
        String amt = "\"amt\":{\"id\":\"amt\",\"type\":\"number\",\"attributes\":{\"key\":\"amt\",\"required\":true}}";
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"entered\",\"currency\":\"USD\",\"amountFrom\":\"amt\",\"maxAmountMinor\":1000,\"minAmountMinor\":null", amt)))
                .anyMatch(p -> p.contains("minimum payment amount must be positive"));
    }

    @Test
    void aMaximumBelowTheCurrencyMinimumIsReported() {
        String amt = "\"amt\":{\"id\":\"amt\",\"type\":\"number\",\"attributes\":{\"key\":\"amt\",\"required\":true}}";
        String entered = "\"amountMode\":\"entered\",\"currency\":\"USD\",\"amountFrom\":\"amt\",";
        assertThat(PaymentAmountResolver.configProblems(schema(entered + "\"maxAmountMinor\":40", amt)))
                .anyMatch(p -> p.contains("below the smallest amount"));
        assertThat(PaymentAmountResolver.configProblems(schema(entered + "\"maxAmountMinor\":40,\"minAmountMinor\":10", amt))).isEmpty();
        assertThat(PaymentAmountResolver.configProblems(schema(entered + "\"maxAmountMinor\":50", amt))).isEmpty();
    }

    @Test
    void anEnteredAmountsCurrencyFieldMustShowTheChargeCurrency() {
        String eur = "\"amt\":{\"id\":\"amt\",\"type\":\"currency\",\"attributes\":{\"key\":\"amt\",\"required\":true,\"currencyCode\":\"EUR\"}}";
        String entered = "\"amountMode\":\"entered\",\"amountFrom\":\"amt\",\"maxAmountMinor\":100000,";
        assertThat(PaymentAmountResolver.configProblems(schema(entered + "\"currency\":\"USD\"", eur)))
                .containsExactly("The payment amount field \"amt\" must show USD.");
        assertThat(PaymentAmountResolver.configProblems(schema(entered + "\"currency\":\"eur\"", eur))).isEmpty();
    }

    @Test
    void aWizardsPaymentMustBeOnTheLastPage() {
        String pay = "\"pay\":{\"id\":\"pay\",\"type\":\"payment\",\"attributes\":{\"key\":\"pay\",\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\"}}";
        java.util.function.Function<String, String> wizard = (home) -> "{\"root\":[\"w\"],\"entities\":{"
                + "\"w\":{\"id\":\"w\",\"type\":\"wizard\",\"attributes\":{},\"children\":[\"p1\",\"p2\"]},"
                + "\"p1\":{\"id\":\"p1\",\"type\":\"page\",\"attributes\":{},\"children\":[" + ("p1".equals(home) ? "\"pay\"" : "") + "]},"
                + "\"p2\":{\"id\":\"p2\",\"type\":\"page\",\"attributes\":{},\"children\":[" + ("p2".equals(home) ? "\"pay\"" : "") + "]},"
                + pay + "}}";
        assertThat(PaymentAmountResolver.configProblems(wizard.apply("p1")))
                .containsExactly("In a multi-page form the payment field must be on the last page.");
        assertThat(PaymentAmountResolver.configProblems(wizard.apply("p2"))).isEmpty();
        String rootPages = "{\"root\":[\"p1\",\"p2\"],\"entities\":{"
                + "\"p1\":{\"id\":\"p1\",\"type\":\"page\",\"attributes\":{},\"children\":[\"pay\"]},"
                + "\"p2\":{\"id\":\"p2\",\"type\":\"page\",\"attributes\":{},\"children\":[]}," + pay + "}}";
        assertThat(PaymentAmountResolver.configProblems(rootPages)).hasSize(1);
    }

    @Test
    void containersComeFromChildrenAndFlagsUseJavaScriptTruthiness() {
        String inHiddenPanel = "{\"root\":[\"p\"],\"entities\":{"
                + "\"p\":{\"id\":\"p\",\"type\":\"panel\",\"attributes\":{\"hidden\":\"false\"},\"children\":[\"pay\"]},"
                + "\"pay\":{\"id\":\"pay\",\"type\":\"payment\",\"attributes\":{\"key\":\"pay\",\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\"}}}}";
        assertThat(PaymentAmountResolver.configProblems(inHiddenPanel)).anyMatch(p -> p.contains("conditional"));
        assertThat(PaymentAmountResolver.resolve(inHiddenPanel, Map.of()).failure())
                .isEqualTo(PaymentAmountResolver.Failure.PAYMENT_FIELD_CONDITIONAL);
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        assertThat(PaymentAmountResolver.jsTruthy(f.textNode("false"))).isTrue();
        assertThat(PaymentAmountResolver.jsTruthy(f.textNode(""))).isFalse();
        assertThat(PaymentAmountResolver.jsTruthy(f.numberNode(0))).isFalse();
        assertThat(PaymentAmountResolver.jsTruthy(f.numberNode(0.5))).isTrue();
        assertThat(PaymentAmountResolver.jsTruthy(f.objectNode())).isTrue();
        assertThat(PaymentAmountResolver.jsTruthy(f.nullNode())).isFalse();
        assertThat(PaymentAmountResolver.jsTruthy(null)).isFalse();
    }

    @Test
    void anUnplacedPaymentOrAStraySubmitButtonIsReported() {
        String orphan = "{\"root\":[],\"entities\":{\"pay\":{\"id\":\"pay\",\"type\":\"payment\",\"attributes\":{\"key\":\"pay\",\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\"}}}}";
        assertThat(PaymentAmountResolver.configProblems(orphan)).contains("The payment field isn't placed on the form.");
        String pay = "\"pay\":{\"id\":\"pay\",\"type\":\"payment\",\"attributes\":{\"key\":\"pay\",\"amountMode\":\"fixed\",\"amountMinor\":500,\"currency\":\"USD\"}}";
        java.util.function.Function<String, String> wizard = (buttonAction) -> "{\"root\":[\"w\"],\"entities\":{"
                + "\"w\":{\"id\":\"w\",\"type\":\"wizard\",\"attributes\":{},\"children\":[\"p1\",\"p2\"]},"
                + "\"p1\":{\"id\":\"p1\",\"type\":\"page\",\"attributes\":{},\"children\":[\"b\"]},"
                + "\"p2\":{\"id\":\"p2\",\"type\":\"page\",\"attributes\":{},\"children\":[\"pay\"]},"
                + "\"b\":{\"id\":\"b\",\"type\":\"button\",\"attributes\":{" + buttonAction + "}}," + pay + "}}";
        assertThat(PaymentAmountResolver.configProblems(wizard.apply("")))
                .containsExactly("In a multi-page form that takes a payment, submit buttons must be on the last page.");
        assertThat(PaymentAmountResolver.configProblems(wizard.apply("\"buttonAction\":\"submit\""))).hasSize(1);
        assertThat(PaymentAmountResolver.configProblems(wizard.apply("\"buttonAction\":\"button\""))).isEmpty();
        // A numeric root reference is read like a property key.
        String numericWizard = "{\"root\":[5],\"entities\":{"
                + "\"5\":{\"id\":\"5\",\"type\":\"wizard\",\"attributes\":{},\"children\":[\"p1\",\"p2\"]},"
                + "\"p1\":{\"id\":\"p1\",\"type\":\"page\",\"attributes\":{},\"children\":[\"pay\"]},"
                + "\"p2\":{\"id\":\"p2\",\"type\":\"page\",\"attributes\":{},\"children\":[]}," + pay + "}}";
        assertThat(PaymentAmountResolver.configProblems(numericWizard))
                .containsExactly("In a multi-page form the payment field must be on the last page.");
    }

    @Test
    void aTooPreciseAmountFieldIsReported() {
        String jpy = "\"amt\":{\"id\":\"amt\",\"type\":\"currency\",\"attributes\":{\"key\":\"amt\",\"required\":true,\"currencyCode\":\"JPY\",\"decimalLimit\":2}}";
        assertThat(PaymentAmountResolver.configProblems(schema(
                "\"amountMode\":\"entered\",\"amountFrom\":\"amt\",\"maxAmountMinor\":100000,\"currency\":\"JPY\"", jpy)))
                .containsExactly("The payment amount field \"amt\" allows more decimal places than JPY can charge.");
    }

    @Test
    void jsTrimMatchesJavaScript() {
        assertThat(PaymentAmountResolver.jsTrim("   12 ﻿\n")).isEqualTo("12");
        assertThat(PaymentAmountResolver.jsTrim("​12")).isEqualTo("​12"); // zero-width space is NOT JS whitespace
        assertThat(PaymentAmountResolver.answerNumber(" 12.5")).isEqualTo(12.5);
        assertThat(PaymentAmountResolver.answerNumber("12.5f")).isNull();
        assertThat(PaymentAmountResolver.answerNumber("NaN")).isNull();
        assertThat(PaymentAmountResolver.answerNumber(Boolean.TRUE)).isNull();
        assertThat(PaymentAmountResolver.answerNumber(List.of(1))).isNull();
        assertThat(PaymentAmountResolver.answerNumber(new java.math.BigDecimal("3.5"))).isEqualTo(3.5);
        assertThat(PaymentAmountResolver.answerNumber("1" + "0".repeat(400))).isNull();
    }

    @Test
    void toMinorUnitsRoundsTheExactBinaryValueHalfAwayFromZero() {
        assertThat(PaymentAmountResolver.toMinorUnits(1.005, 2)).isEqualTo(100);
        assertThat(PaymentAmountResolver.toMinorUnits(0.625, 2)).isEqualTo(63);
        assertThat(PaymentAmountResolver.toMinorUnits(-0.625, 2)).isEqualTo(-63);
        assertThat(PaymentAmountResolver.toMinorUnits(2.5, 0)).isEqualTo(3);
        assertThat(PaymentAmountResolver.toMinorUnits(1e16, 2)).isNull();
        assertThat(PaymentAmountResolver.toMinorUnits(Double.NaN, 2)).isNull();
    }

    @Test
    void payerMessagesMatchFormCore() {
        assertThat(PaymentAmountResolver.Failure.QUANTITY_OUT_OF_RANGE.payerMessage()).isEqualTo("That quantity isn't available.");
        assertThat(PaymentAmountResolver.Failure.INVALID_MODE.isPayerFixable()).isFalse();
        assertThat(PaymentAmountResolver.Failure.INVALID_MODE.payerMessage())
                .isEqualTo(PaymentAmountResolver.Failure.INELIGIBLE_AMOUNT_SOURCE.payerMessage());
    }
}
