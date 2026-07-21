package com.luke.engine.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.delegate.BpmnError;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link HttpResponseParser} — no Spring/engine needed. Proves the parser is
 * universal: it normalizes any HTTP response (JSON object/array, plain text, empty, error) the same
 * way, regardless of which API produced it.
 */
class HttpResponseParserTest {

    private final HttpResponseParser parser = new HttpResponseParser(new ObjectMapper());

    private static Map<String, String> headers(String contentType) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", contentType); // deliberately mixed-case to prove normalization
        return h;
    }

    @Test
    void parsesJsonObjectBodyIntoANavigableMap() {
        Map<String, Object> r = parser.parse(200, "{\"id\":7,\"name\":\"acme\"}", headers("application/json; charset=utf-8"));

        assertThat(r.get("statusCode")).isEqualTo(200);
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("contentType")).isEqualTo("application/json"); // params stripped, lower-cased
        assertThat(r.get("body")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.get("body");
        assertThat(body).containsEntry("id", 7).containsEntry("name", "acme");
        assertThat(r.get("raw")).isEqualTo("{\"id\":7,\"name\":\"acme\"}");
    }

    @Test
    void parsesJsonArrayBody() {
        Map<String, Object> r = parser.parse(200, "[1,2,3]", headers("application/json"));
        assertThat(r.get("body")).isInstanceOf(List.class).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    void detectsJsonByShapeEvenWithoutAJsonContentType() {
        Map<String, Object> r = parser.parse(200, "{\"a\":1}", headers("text/plain"));
        assertThat(r.get("body")).isInstanceOf(Map.class);
    }

    @Test
    void keepsNonJsonBodyAsRawString() {
        Map<String, Object> r = parser.parse(200, "hello world", headers("text/plain"));
        assertThat(r.get("body")).isEqualTo("hello world");
    }

    @Test
    void malformedJsonFallsBackToRawStringInsteadOfFailing() {
        Map<String, Object> r = parser.parse(200, "{not valid json", headers("application/json"));
        assertThat(r.get("body")).isEqualTo("{not valid json");
        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    void emptyOrNullBodyBecomesNull() {
        assertThat(parser.parse(204, "", headers("application/json")).get("body")).isNull();
        assertThat(parser.parse(204, null, null).get("body")).isNull();
    }

    @Test
    void normalizesHeaderKeysToLowerCase() {
        @SuppressWarnings("unchecked")
        Map<String, String> hdrs = (Map<String, String>) parser.parse(200, "{}", headers("application/json")).get("headers");
        assertThat(hdrs).containsKey("content-type").doesNotContainKey("Content-Type");
    }

    @Test
    void coercesStringStatusCodes() {
        assertThat(parser.parse("200", "{}", null).get("statusCode")).isEqualTo(200);
        assertThat(parser.parse("200", "{}", null).get("ok")).isEqualTo(true);
    }

    @Test
    void marksNon2xxAsNotOkWithoutThrowing() {
        Map<String, Object> r = parser.parse(404, "{\"error\":\"nope\"}", headers("application/json"));
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("statusCode")).isEqualTo(404);
    }

    @Test
    void parseOrThrowReturnsResultOn2xx() {
        Map<String, Object> r = parser.parseOrThrow(201, "{\"created\":true}", headers("application/json"));
        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    void parseOrThrowRaisesBpmnErrorOnNon2xxWithStatusAndSnippet() {
        BpmnError err = catchThrowableOfType(
                () -> parser.parseOrThrow(500, "internal boom", headers("text/plain")),
                BpmnError.class);
        assertThat(err).isNotNull();
        assertThat(err.getErrorCode()).isEqualTo(HttpResponseParser.ERR_HTTP);
        assertThat(err.getMessage()).contains("500").contains("internal boom");
    }

    @Test
    void parseOrThrowHonoursACustomErrorCode() {
        assertThatThrownBy(() -> parser.parseOrThrow(429, "slow down", null, "rate-limited"))
                .isInstanceOf(BpmnError.class)
                .satisfies(t -> assertThat(((BpmnError) t).getErrorCode()).isEqualTo("rate-limited"));
    }
}
