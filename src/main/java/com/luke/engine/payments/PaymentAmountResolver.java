package com.luke.engine.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Decides what a form submission is charged — the server half of form-core's
 * {@code resolvePaymentAmount}, and the one whose answer actually reaches Stripe.
 *
 * <p><b>The amount is never read from the client.</b> It is recomputed from the served version's
 * schema plus the submitted answers, and the payment field's own value (the one thing a browser
 * controls outright) is ignored. Every supported mode takes at most ONE respondent-controlled number,
 * and only from a plain field the respondent is entitled to fill in anyway:
 *
 * <ul>
 *   <li>{@code fixed} — the schema's {@code amountMinor};</li>
 *   <li>{@code perUnit} — {@code amountMinor × quantity}, quantity bounded by {@code maxQuantity};</li>
 *   <li>{@code entered} — the respondent's amount, bounded by {@code minAmountMinor}/{@code maxAmountMinor}.</li>
 * </ul>
 *
 * <p>There is deliberately no "calculated" mode: this server does not evaluate form expressions, so a
 * value the browser computed ({@code price * qty}) would be whatever the browser said.
 *
 * <p><b>Parity.</b> The checks run in the same order as the TypeScript implementation and return the
 * same reason codes; {@code payment-parity.json} (authored in luke-forms) runs in both languages. Two
 * details that make exact agreement possible: strings are accepted only as plain decimals (JavaScript
 * {@code Number()} and Java {@code parseDouble} disagree on hex, exponents and type suffixes), and
 * major→minor conversion rounds the double's EXACT binary value half away from zero — which is what
 * JavaScript's {@code toFixed} does and what {@code new BigDecimal(double)} + {@code HALF_UP} does.
 *
 * <p>Pure and stateless. Never throws on bad input: every failure is a {@link Resolution} with a reason.
 */
public final class PaymentAmountResolver {

    public static final String PAYMENT_TYPE = "payment";
    public static final int DEFAULT_MAX_QUANTITY = 100;
    /** Processors cap the amount at eight digits (999,999.99 USD). */
    public static final long MAX_CHARGE_MINOR = 99_999_999L;
    static final List<String> QUANTITY_SOURCE_TYPES = List.of("number");
    static final List<String> AMOUNT_SOURCE_TYPES = List.of("number", "currency");

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L; // 2^53 - 1, JavaScript's limit
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Set<String> MODES = Set.of("fixed", "perUnit", "entered");
    private static final Set<String> GRID_TYPES = Set.of("dataGrid", "editGrid");
    private static final Set<String> DYNAMIC_ACTIONS =
            Set.of("show", "hide", "require", "optional", "enable", "disable", "setValue");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaymentAmountResolver() {}

    /** Why an amount could not be resolved. {@link #code()} matches form-core's reason strings. */
    public enum Failure {
        NO_PAYMENT_FIELD("no-payment-field"),
        MULTIPLE_PAYMENT_FIELDS("multiple-payment-fields"),
        PAYMENT_FIELD_CONDITIONAL("payment-field-conditional"),
        UNSUPPORTED_CURRENCY("unsupported-currency"),
        INVALID_MODE("invalid-mode"),
        MISSING_AMOUNT("missing-amount"),
        MISSING_AMOUNT_SOURCE("missing-amount-source"),
        INELIGIBLE_AMOUNT_SOURCE("ineligible-amount-source"),
        AMOUNT_SOURCE_EMPTY("amount-source-empty"),
        AMOUNT_NOT_NUMERIC("amount-not-numeric"),
        QUANTITY_NOT_WHOLE("quantity-not-whole"),
        QUANTITY_OUT_OF_RANGE("quantity-out-of-range"),
        AMOUNT_BELOW_MINIMUM("amount-below-minimum"),
        AMOUNT_ABOVE_MAXIMUM("amount-above-maximum"),
        AMOUNT_NOT_CHARGEABLE("amount-not-chargeable");

        private final String code;

        Failure(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** Whether the RESPONDENT can fix this by changing an answer (vs an author configuration fault). */
        public boolean isPayerFixable() {
            return switch (this) {
                case AMOUNT_SOURCE_EMPTY, AMOUNT_NOT_NUMERIC, QUANTITY_NOT_WHOLE, QUANTITY_OUT_OF_RANGE,
                        AMOUNT_BELOW_MINIMUM, AMOUNT_ABOVE_MAXIMUM, AMOUNT_NOT_CHARGEABLE -> true;
                default -> false;
            };
        }

        /** The payer-facing sentence — identical wording to form-core's {@code describePaymentFailure}. */
        public String payerMessage() {
            return switch (this) {
                case AMOUNT_SOURCE_EMPTY -> "Enter the amount or quantity to pay for.";
                case AMOUNT_NOT_NUMERIC -> "The amount or quantity must be a number.";
                case QUANTITY_NOT_WHOLE -> "The quantity must be a whole number.";
                case QUANTITY_OUT_OF_RANGE -> "That quantity isn't available.";
                case AMOUNT_BELOW_MINIMUM -> "That amount is below the minimum.";
                case AMOUNT_ABOVE_MAXIMUM -> "That amount is above the maximum.";
                case AMOUNT_NOT_CHARGEABLE -> "That amount can't be charged.";
                default -> "This form's payment isn't set up correctly. Please contact the form owner.";
            };
        }
    }

    /** The outcome: either a priced charge or a failure. */
    public record Resolution(boolean ok, Failure failure, String entityId, String key, long amountMinor,
                             String currency, String description, String mode, Integer quantity) {

        static Resolution fail(Failure f, String entityId) {
            return new Resolution(false, f, entityId, null, 0, null, null, null, null);
        }
    }

    /** A payment field found in a schema. */
    public record PaymentField(String id, JsonNode entity) {}

    /* ── schema reading ───────────────────────────────────────────────────── */

    /** Parse a schema JSON string; a blank or malformed schema reads as an empty object. */
    public static JsonNode parse(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return JsonNodeFactory.instance.objectNode();
        try {
            JsonNode n = MAPPER.readTree(schemaJson);
            return n == null ? JsonNodeFactory.instance.objectNode() : n;
        } catch (Exception e) {
            return JsonNodeFactory.instance.objectNode();
        }
    }

    /**
     * The schema's entities as an id → entity object, read the way the browser engine reads
     * {@code entities[id]} (form-core's {@code entityMap}): an ARRAY is keyed by position ("0", "1", …),
     * and only object entries count. Shared with the submission validator ({@code FormSupport}), so an
     * array-shaped schema is validated like any other.
     */
    public static JsonNode entities(JsonNode schema) {
        JsonNode e = schema == null ? null : schema.get("entities");
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (e != null && e.isArray()) {
            for (int i = 0; i < e.size(); i++) {
                if (e.get(i) != null && e.get(i).isObject()) out.set(String.valueOf(i), e.get(i));
            }
        } else if (e != null && e.isObject()) {
            e.fields().forEachRemaining(f -> {
                if (f.getValue().isObject()) out.set(f.getKey(), f.getValue());
            });
        }
        return out;
    }

    /**
     * A {@code root}/{@code children} entry as a property key (form-core's {@code refKey}): a string as
     * is, a non-negative safe integer or a boolean as its text; anything else names no entity.
     */
    static String refKey(JsonNode entry) {
        if (entry == null) return null;
        if (entry.isTextual()) return entry.textValue();
        if (entry.isBoolean()) return String.valueOf(entry.booleanValue());
        if (entry.isNumber()) {
            double d = entry.doubleValue();
            return isSafeInteger(d) && d >= 0 ? String.valueOf((long) d) : null;
        }
        return null;
    }

    private static List<String> refKeys(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode n : list) {
                String k = refKey(n);
                if (k != null) out.add(k);
            }
        }
        return out;
    }

    /** Every id reachable from {@code root} through {@code children} (form-core's {@code reachableIds}). */
    static Set<String> reachableIds(JsonNode schema) {
        JsonNode entities = entities(schema);
        Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        List<String> root = refKeys(schema == null ? null : schema.get("root"));
        for (int i = root.size() - 1; i >= 0; i--) stack.push(root.get(i));
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (seen.contains(id) || !entities.has(id)) continue;
            seen.add(id);
            List<String> kids = refKeys(entities.get(id).get("children"));
            for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
        }
        return seen;
    }

    private static TreeSet<String> sortedIds(JsonNode entities) {
        TreeSet<String> ids = new TreeSet<>();
        entities.fieldNames().forEachRemaining(ids::add);
        return ids;
    }

    private static JsonNode attrs(JsonNode entity) {
        JsonNode a = entity == null ? null : entity.get("attributes");
        return a != null && a.isObject() ? a : JsonNodeFactory.instance.objectNode();
    }

    private static String text(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static String type(JsonNode entity) {
        JsonNode t = entity == null ? null : entity.get("type");
        return t != null && t.isTextual() ? t.textValue() : "";
    }

    /** Every payment-typed entity, in id order. */
    public static List<PaymentField> findPaymentFields(JsonNode schema) {
        JsonNode entities = entities(schema);
        List<PaymentField> out = new ArrayList<>();
        for (String id : sortedIds(entities)) {
            JsonNode e = entities.get(id);
            if (e != null && e.isObject() && PAYMENT_TYPE.equals(type(e))) out.add(new PaymentField(id, e));
        }
        return out;
    }

    public static boolean hasPayment(String schemaJson) {
        return !findPaymentFields(parse(schemaJson)).isEmpty();
    }

    /** A number attribute as JavaScript would see it, or empty when absent / not a number. */
    private static Optional<Double> numberAttr(JsonNode a, String field) {
        JsonNode v = a.get(field);
        return v != null && v.isNumber() ? Optional.of(v.doubleValue()) : Optional.empty();
    }

    /** JavaScript {@code Number.isSafeInteger(v) && v > 0}. */
    private static boolean positiveInt(Optional<Double> v) {
        if (v.isEmpty()) return false;
        double d = v.get();
        return isSafeInteger(d) && d > 0;
    }

    private static boolean isSafeInteger(double d) {
        return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) <= MAX_SAFE_INTEGER;
    }

    /* ── conditionality (mirror of form-core isConditionallyControlled) ─────── */

    /**
     * {@code hidden}/{@code disabled} are read with JavaScript truthiness, as the browser engine reads
     * them ({@code Boolean(value)}) — so {@code "true"}, {@code 1} and even {@code "false"} count.
     * {@code persistent} is compared strictly with {@code false}.
     */
    static boolean isConditionallyControlled(JsonNode entity) {
        JsonNode a = attrs(entity);
        if (jsTruthy(a.get("hidden")) || jsTruthy(a.get("disabled")) || isBool(a, "persistent", false)) return true;
        if (nonBlank(a, "customConditional") || nonBlank(a, "customConditionalJs")) return true;
        if (nonBlank(a, "calculateValue") || nonBlank(a, "calculateValueJs")) return true;
        JsonNode cond = a.get("conditional");
        if (cond != null && cond.isObject() && cond.size() > 0) return true;
        JsonNode logic = a.get("logic");
        if (logic != null && logic.isArray()) {
            for (JsonNode rule : logic) {
                if (rule != null && rule.isObject()) {
                    String action = text(rule, "action");
                    if (action != null && DYNAMIC_ACTIONS.contains(action)) return true;
                }
            }
        }
        return false;
    }

    /** JavaScript's {@code Boolean(value)} for a JSON value; absent reads as {@code undefined}. */
    static boolean jsTruthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return false;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isNumber()) {
            double d = v.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (v.isTextual()) return !v.textValue().isEmpty();
        return true; // objects and arrays
    }

    private static boolean isBool(JsonNode a, String field, boolean expected) {
        JsonNode v = a.get(field);
        return v != null && v.isBoolean() && v.booleanValue() == expected;
    }

    private static boolean nonBlank(JsonNode a, String field) {
        String s = text(a, field);
        return s != null && !jsTrim(s).isEmpty();
    }

    /**
     * Each entity's container, from the containers' {@code children} lists — what the renderer and the
     * browser engine use ({@code parentId} is advisory). The first container by id wins.
     */
    private static Map<String, String> parentMap(JsonNode entities) {
        Map<String, String> parents = new HashMap<>();
        for (String id : sortedIds(entities)) {
            for (String c : refKeys(entities.get(id).get("children"))) parents.putIfAbsent(c, id);
        }
        return parents;
    }

    /** The ids of an entity and its containers (nearest first); stops at the top or on a cycle. */
    static List<String> selfAndAncestorIds(JsonNode schema, String id) {
        JsonNode entities = entities(schema);
        Map<String, String> parents = parentMap(entities);
        List<String> out = new ArrayList<>();
        String cur = id;
        while (cur != null && !out.contains(cur)) {
            JsonNode e = entities.get(cur);
            if (e == null || !e.isObject()) break;
            out.add(cur);
            cur = parents.get(cur);
        }
        return out;
    }

    /** The entity and its containers (nearest first). */
    static List<JsonNode> selfAndAncestors(JsonNode schema, String id) {
        JsonNode entities = entities(schema);
        return selfAndAncestorIds(schema, id).stream().map(entities::get).toList();
    }

    static boolean isInsideGrid(JsonNode schema, String id) {
        List<JsonNode> chain = selfAndAncestors(schema, id);
        return chain.stream().skip(1).anyMatch(e -> GRID_TYPES.contains(type(e)));
    }

    /**
     * Why the payment field ITSELF can't take a charge the server can trust: {@code in-grid},
     * {@code excluded}, or {@code conditional} (itself or any container); null when sound. Mirrors
     * form-core's {@code paymentFieldProblem}.
     */
    static String paymentFieldProblem(JsonNode schema, String id) {
        List<JsonNode> chain = selfAndAncestors(schema, id);
        if (chain.isEmpty()) return null;
        if (!reachableIds(schema).contains(id)) return "unreachable";
        if (isInsideGrid(schema, id)) return "in-grid";
        if (isBool(attrs(chain.get(0)), "persistent", false)) return "excluded";
        boolean selfConditional = isConditionallyControlled(withoutPersistent(chain.get(0)));
        if (selfConditional || chain.stream().skip(1).anyMatch(PaymentAmountResolver::isConditionallyControlled)) {
            return "conditional";
        }
        return null;
    }

    /** The form's wizard pages in order, or null when it isn't a wizard (form-core {@code wizardPageIds}). */
    static List<String> wizardPageIds(JsonNode schema) {
        JsonNode entities = entities(schema);
        JsonNode rootNode = schema == null ? null : schema.get("root");
        List<String> root = new ArrayList<>();
        if (rootNode != null && rootNode.isArray()) {
            for (JsonNode r : rootNode) root.add(refKey(r));
        }
        if (root.size() == 1 && root.get(0) != null && "wizard".equals(type(entities.get(root.get(0))))) {
            List<String> pages = new ArrayList<>();
            for (String c : refKeys(entities.get(root.get(0)).get("children"))) {
                if ("page".equals(type(entities.get(c)))) pages.add(c);
            }
            if (!pages.isEmpty()) return pages;
        }
        List<String> pages = root.stream().filter(r -> r != null && "page".equals(type(entities.get(r)))).toList();
        return !pages.isEmpty() && pages.size() == root.size() ? pages : null;
    }

    /** A button that renders as a submit button (its action isn't reset or plain). */
    static boolean isSubmitButton(JsonNode entity) {
        if (!"button".equals(type(entity))) return false;
        JsonNode action = attrs(entity).get("buttonAction");
        return action == null || !(action.isTextual()
                && ("reset".equals(action.textValue()) || "button".equals(action.textValue())));
    }

    /** Every submit button the renderer draws, in id order. */
    static List<String> reachableSubmitButtonIds(JsonNode schema) {
        JsonNode entities = entities(schema);
        return reachableIds(schema).stream().filter(id -> isSubmitButton(entities.get(id))).sorted().toList();
    }

    /**
     * The currency a {@code currency} field shows, as the renderer picks it: {@code currency}, else
     * {@code currencyCode}, else USD. Null when that names no supported currency.
     */
    static String sourceCurrencyOf(JsonNode entity) {
        JsonNode a = attrs(entity);
        String raw = text(a, "currency");
        if (raw == null) raw = text(a, "currencyCode");
        if (raw == null) raw = "USD";
        return Currencies.of(raw).map(Currencies.Info::code).orElse(null);
    }

    /** The entity whose explicit {@code key} attribute is {@code key}, first by id. */
    static Optional<PaymentField> findFieldByKey(JsonNode schema, String key) {
        JsonNode entities = entities(schema);
        for (String id : sortedIds(entities)) {
            JsonNode e = entities.get(id);
            if (e != null && e.isObject() && key.equals(text(attrs(e), "key"))) return Optional.of(new PaymentField(id, e));
        }
        return Optional.empty();
    }

    /**
     * Why {@code key} can't feed an amount: missing / wrong-type / in-grid / conditional /
     * currency-mismatch (a {@code currency} field showing a currency other than {@code currency}, when
     * one is given), or null.
     */
    static String amountSourceProblem(JsonNode schema, String key, List<String> allowedTypes, String currency) {
        if (key == null || key.isEmpty()) return "missing";
        Optional<PaymentField> found = findFieldByKey(schema, key);
        if (found.isEmpty()) return "missing";
        if (!allowedTypes.contains(type(found.get().entity()))) return "wrong-type";
        if (!reachableIds(schema).contains(found.get().id())) return "unreachable";
        if (isInsideGrid(schema, found.get().id())) return "in-grid";
        if (selfAndAncestors(schema, found.get().id()).stream().anyMatch(PaymentAmountResolver::isConditionallyControlled)) {
            return "conditional";
        }
        if (currency != null) {
            if ("currency".equals(type(found.get().entity())) && !currency.equals(sourceCurrencyOf(found.get().entity()))) {
                return "currency-mismatch";
            }
            Optional<Double> places = numberAttr(attrs(found.get().entity()), "decimalLimit");
            Optional<Currencies.Info> info = Currencies.of(currency);
            if (places.isPresent() && info.isPresent() && places.get() > info.get().exponent()) return "too-precise";
        }
        return null;
    }

    /* ── resolution ───────────────────────────────────────────────────────── */

    public static Resolution resolve(String schemaJson, Map<String, Object> data) {
        return resolve(parse(schemaJson), data);
    }

    /** Price a submission. The payment field's own value in {@code data} is never read. */
    public static Resolution resolve(JsonNode schema, Map<String, Object> data) {
        List<PaymentField> fields = findPaymentFields(schema);
        if (fields.isEmpty()) return Resolution.fail(Failure.NO_PAYMENT_FIELD, null);
        if (fields.size() > 1) return Resolution.fail(Failure.MULTIPLE_PAYMENT_FIELDS, fields.get(0).id());

        String entityId = fields.get(0).id();
        if (paymentFieldProblem(schema, entityId) != null) return Resolution.fail(Failure.PAYMENT_FIELD_CONDITIONAL, entityId);
        JsonNode a = attrs(fields.get(0).entity());
        Map<String, Object> answers = data == null ? Map.of() : data;

        Optional<Currencies.Info> currency = Currencies.of(text(a, "currency"));
        if (currency.isEmpty()) return Resolution.fail(Failure.UNSUPPORTED_CURRENCY, entityId);
        String mode = text(a, "amountMode");
        if (mode == null || !MODES.contains(mode)) return Resolution.fail(Failure.INVALID_MODE, entityId);

        long amountMinor;
        Integer quantity = null;
        if (mode.equals("fixed") || mode.equals("perUnit")) {
            Optional<Double> fixed = numberAttr(a, "amountMinor");
            if (!positiveInt(fixed)) return Resolution.fail(Failure.MISSING_AMOUNT, entityId);
            amountMinor = fixed.get().longValue();
            if (mode.equals("perUnit")) {
                String source = text(a, "quantityFrom");
                if (source == null || source.isEmpty()) return Resolution.fail(Failure.MISSING_AMOUNT_SOURCE, entityId);
                if (amountSourceProblem(schema, source, QUANTITY_SOURCE_TYPES, null) != null) {
                    return Resolution.fail(Failure.INELIGIBLE_AMOUNT_SOURCE, entityId);
                }
                Object raw = answers.get(source);
                if (isBlankAnswer(raw)) return Resolution.fail(Failure.AMOUNT_SOURCE_EMPTY, entityId);
                Double q = answerNumber(raw);
                if (q == null) return Resolution.fail(Failure.AMOUNT_NOT_NUMERIC, entityId);
                if (q != Math.rint(q)) return Resolution.fail(Failure.QUANTITY_NOT_WHOLE, entityId);
                Optional<Double> maxQ = numberAttr(a, "maxQuantity");
                double max = positiveInt(maxQ) ? maxQ.get() : DEFAULT_MAX_QUANTITY;
                if (q < 1 || q > max) return Resolution.fail(Failure.QUANTITY_OUT_OF_RANGE, entityId);
                // q ≤ max ≤ 2^53, so a long is exact; overflow of the product means "not chargeable".
                long qty = q.longValue();
                try {
                    amountMinor = Math.multiplyExact(amountMinor, qty);
                } catch (ArithmeticException overflow) {
                    return Resolution.fail(Failure.AMOUNT_NOT_CHARGEABLE, entityId);
                }
                quantity = qty > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) qty;
            }
        } else {
            String source = text(a, "amountFrom");
            if (source == null || source.isEmpty()) return Resolution.fail(Failure.MISSING_AMOUNT_SOURCE, entityId);
            if (amountSourceProblem(schema, source, AMOUNT_SOURCE_TYPES, currency.get().code()) != null) {
                return Resolution.fail(Failure.INELIGIBLE_AMOUNT_SOURCE, entityId);
            }
            Optional<Double> maxAmount = numberAttr(a, "maxAmountMinor");
            if (!positiveInt(maxAmount)) return Resolution.fail(Failure.MISSING_AMOUNT, entityId);
            Object raw = answers.get(source);
            if (isBlankAnswer(raw)) return Resolution.fail(Failure.AMOUNT_SOURCE_EMPTY, entityId);
            Double major = answerNumber(raw);
            Long minor = major == null ? null : toMinorUnits(major, currency.get().exponent());
            if (minor == null) return Resolution.fail(Failure.AMOUNT_NOT_NUMERIC, entityId);
            Optional<Double> minAmount = numberAttr(a, "minAmountMinor");
            double min = positiveInt(minAmount) ? minAmount.get() : currency.get().minimumMinor();
            if (minor < min) return Resolution.fail(Failure.AMOUNT_BELOW_MINIMUM, entityId);
            if (minor > maxAmount.get()) return Resolution.fail(Failure.AMOUNT_ABOVE_MAXIMUM, entityId);
            amountMinor = minor;
        }

        if (!withinCharge(amountMinor, currency.get())) return Resolution.fail(Failure.AMOUNT_NOT_CHARGEABLE, entityId);

        String key = text(a, "key");
        if (key == null || key.isEmpty()) key = entityId;
        String description = text(a, "chargeDescription");
        return new Resolution(true, null, entityId, key, amountMinor, currency.get().code(),
                description == null ? "" : description, mode, quantity);
    }

    private static boolean isBlankAnswer(Object raw) {
        return raw == null || "".equals(raw);
    }

    /** A respondent-supplied number: a finite JSON number or a plain decimal string; else null. */
    static Double answerNumber(Object raw) {
        if (raw instanceof Boolean) return null;
        if (raw instanceof Number n) {
            double d = (n instanceof BigDecimal bd) ? bd.doubleValue()
                    : (n instanceof BigInteger bi) ? bi.doubleValue() : n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (raw instanceof String s) {
            String t = jsTrim(s);
            if (!DECIMAL.matcher(t).matches()) return null;
            double d = Double.parseDouble(t); // a very long digit string overflows to Infinity
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    /**
     * Major → minor units, exactly as form-core's {@code toMinorUnits}: round the double's exact
     * binary value to {@code exponent} places, half away from zero, and refuse anything past
     * JavaScript's safe-integer range.
     */
    static Long toMinorUnits(double major, int exponent) {
        if (!Double.isFinite(major)) return null;
        BigInteger unscaled = new BigDecimal(major).setScale(exponent, RoundingMode.HALF_UP).unscaledValue();
        if (unscaled.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) return null;
        return unscaled.longValueExact();
    }

    static boolean withinCharge(long amountMinor, Currencies.Info currency) {
        if (amountMinor <= 0 || amountMinor > MAX_CHARGE_MINOR) return false;
        return currency.exponent() != 3 || amountMinor % 10 == 0;
    }

    /** JavaScript's {@code String.prototype.trim}: strips WhiteSpace and LineTerminator code points. */
    static String jsTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsSpace(s.charAt(start))) start++;
        while (end > start && isJsSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isJsSpace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0
                || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F
                || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    /* ── publish-time checks (the ERROR half of form-core's payment diagnostics) ─────────────── */

    /**
     * Author-facing reasons this schema must not go live with a payment. Empty when it may (or when
     * it takes no payment). Mirrors form-core's publish-blocking payment diagnostics — the builder
     * already refuses these, so this is the server's backstop for a schema from any other source.
     */
    public static List<String> configProblems(String schemaJson) {
        JsonNode schema = parse(schemaJson);
        List<PaymentField> fields = findPaymentFields(schema);
        List<String> out = new ArrayList<>();
        if (fields.isEmpty()) return out;
        if (fields.size() > 1) {
            out.add("The form has " + fields.size() + " payment fields; keep exactly one.");
            return out;
        }
        PaymentField field = fields.get(0);
        JsonNode a = attrs(field.entity());
        Optional<Currencies.Info> currency = Currencies.of(text(a, "currency"));
        if (currency.isEmpty()) out.add("Choose a supported currency for the payment.");
        String mode = text(a, "amountMode");
        if (mode == null || !MODES.contains(mode)) {
            out.add("Choose how the payment amount is decided.");
        } else if (mode.equals("fixed") || mode.equals("perUnit")) {
            Optional<Double> amt = numberAttr(a, "amountMinor");
            if (!positiveInt(amt)) {
                out.add("Set the payment amount.");
            } else if (currency.isPresent() && !withinCharge(amt.get().longValue(), currency.get())) {
                out.add("The payment amount can't be charged in " + currency.get().code() + ".");
            }
            if (mode.equals("perUnit")) {
                sourceProblem(schema, text(a, "quantityFrom"), QUANTITY_SOURCE_TYPES, null, "quantity").ifPresent(out::add);
                Optional<Double> maxQ = numberAttr(a, "maxQuantity");
                if (a.has("maxQuantity") && !positiveInt(maxQ)) {
                    out.add("The maximum quantity must be a whole number of at least 1.");
                } else if (positiveInt(amt)) {
                    double max = maxQ.orElse((double) DEFAULT_MAX_QUANTITY);
                    if (amt.get() * max > MAX_CHARGE_MINOR) out.add("At the maximum quantity the total is larger than a single payment can be.");
                }
            }
        } else {
            sourceProblem(schema, text(a, "amountFrom"), AMOUNT_SOURCE_TYPES,
                    currency.map(Currencies.Info::code).orElse(null), "amount").ifPresent(out::add);
            Optional<Double> max = numberAttr(a, "maxAmountMinor");
            if (!positiveInt(max)) out.add("Set a maximum payment amount.");
            else if (max.get() > MAX_CHARGE_MINOR) out.add("The maximum payment amount is larger than a single payment can be.");
            Optional<Double> min = numberAttr(a, "minAmountMinor");
            if (a.has("minAmountMinor") && !positiveInt(min)) out.add("The minimum payment amount must be positive.");
            else if (positiveInt(min) && positiveInt(max) && min.get() > max.get()) out.add("The minimum payment amount is larger than the maximum.");
            else if (!a.has("minAmountMinor") && positiveInt(max) && currency.isPresent()
                    && max.get() < currency.get().minimumMinor()) {
                out.add("The maximum payment amount is below the smallest amount the form accepts; raise it or set a lower minimum.");
            }
        }
        if (isInsideGrid(schema, field.id())) out.add("A payment field can't be inside a repeating grid.");
        if (isBool(a, "persistent", false)) out.add("The payment field can't be excluded from the submission.");
        List<JsonNode> chain = selfAndAncestors(schema, field.id());
        boolean selfConditional = isConditionallyControlled(withoutPersistent(field.entity()));
        if (selfConditional || chain.stream().skip(1).anyMatch(PaymentAmountResolver::isConditionallyControlled)) {
            out.add("The payment field can't be hidden, disabled, calculated or conditional.");
        }
        if (!reachableIds(schema).contains(field.id())) out.add("The payment field isn't placed on the form.");
        List<String> pages = wizardPageIds(schema);
        if (pages != null) {
            String last = pages.get(pages.size() - 1);
            if (!selfAndAncestorIds(schema, field.id()).contains(last)) {
                out.add("In a multi-page form the payment field must be on the last page.");
            }
            if (reachableSubmitButtonIds(schema).stream().anyMatch(b -> !selfAndAncestorIds(schema, b).contains(last))) {
                out.add("In a multi-page form that takes a payment, submit buttons must be on the last page.");
            }
        }
        return out;
    }

    private static JsonNode withoutPersistent(JsonNode entity) {
        JsonNode copy = entity.deepCopy();
        JsonNode a = copy.get("attributes");
        if (a != null && a.isObject()) ((ObjectNode) a).remove("persistent");
        return copy;
    }

    private static Optional<String> sourceProblem(JsonNode schema, String key, List<String> allowed, String currency, String role) {
        String problem = amountSourceProblem(schema, key, allowed, currency);
        if (problem != null) {
            return Optional.of(switch (problem) {
                case "missing" -> "Choose the field that holds the payment " + role + ".";
                case "wrong-type" -> "The payment " + role + " field \"" + key + "\" has the wrong type.";
                case "in-grid" -> "The payment " + role + " field can't be inside a repeating grid.";
                case "currency-mismatch" -> "The payment " + role + " field \"" + key + "\" must show " + currency + ".";
                case "unreachable" -> "The payment " + role + " field \"" + key + "\" isn't placed on the form.";
                case "too-precise" -> "The payment " + role + " field \"" + key + "\" allows more decimal places than " + currency + " can charge.";
                default -> "The payment " + role + " field \"" + key + "\" can't be hidden, disabled, calculated or conditional.";
            });
        }
        JsonNode source = findFieldByKey(schema, key).map(PaymentField::entity).orElse(null);
        return isBool(attrs(source), "required", true)
                ? Optional.empty()
                : Optional.of("The payment " + role + " field \"" + key + "\" must be required.");
    }
}
