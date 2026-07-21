package com.luke.engine.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.finos.fluxnova.bpm.engine.delegate.BpmnError;
import org.finos.fluxnova.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Integration test for {@link HttpCallDelegate} against a real local {@link HttpServer}: proves it
 * executes the call, parses map/list bodies, and applies each author-selected failure mode. Inputs
 * are the task's input-parameter variables, so tests stub {@code execution.getVariable(...)}.
 */
class HttpCallDelegateTest {

    private HttpServer server;
    private String base;
    private HttpCallDelegate delegate;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route("/obj", 200, "application/json", "{\"id\":7,\"status\":\"ok\"}");
        route("/list", 200, "application/json", "[1,2,3]");
        route("/notfound", 404, "application/json", "{\"error\":\"missing\"}");
        route("/boom", 500, "text/plain", "kaboom");
        route("/unauth", 401, "application/json", "{\"error\":\"nope\"}");
        route("/throttled", 429, "application/json", "{\"error\":\"slow down\"}");
        server.createContext("/echo", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, in.length);
            ex.getResponseBody().write(in);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        ObjectMapper mapper = new ObjectMapper();
        delegate = new HttpCallDelegate(new HttpResponseParser(mapper), mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void route(String path, int status, String contentType, String bodyStr) {
        server.createContext(path, ex -> {
            byte[] out = bodyStr.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
    }

    /** A DelegateExecution whose getVariable(name) returns the configured task inputs. */
    private DelegateExecution execWith(Map<String, Object> vars) {
        DelegateExecution ex = mock(DelegateExecution.class);
        when(ex.getVariable(any())).thenReturn(null);
        vars.forEach((k, v) -> when(ex.getVariable(k)).thenReturn(v));
        return ex;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resultVar(DelegateExecution ex, String name) {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(ex).setVariable(eq(name), cap.capture());
        return (Map<String, Object>) cap.getValue();
    }

    @Test
    void get200ObjectStoresParsedMap() {
        DelegateExecution ex = execWith(Map.of("url", base + "/obj"));
        delegate.execute(ex);

        Map<String, Object> r = resultVar(ex, "httpResult");
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("statusCode")).isEqualTo(200);
        assertThat(r.get("body")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) r.get("body")).get("status")).isEqualTo("ok");
    }

    @Test
    void get200ArrayStoresParsedList() {
        DelegateExecution ex = execWith(Map.of("url", base + "/list", "resultVariable", "out"));
        delegate.execute(ex);
        assertThat(resultVar(ex, "out").get("body")).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    void postSendsBodyAndParsesEcho() {
        DelegateExecution ex = execWith(Map.of("url", base + "/echo", "method", "POST", "body", "{\"a\":1}"));
        delegate.execute(ex);
        assertThat(((Map<?, ?>) resultVar(ex, "httpResult").get("body")).get("a")).isEqualTo(1);
    }

    @Test
    void clientError_modeError_raisesBpmnError_andStillSetsResult() {
        DelegateExecution ex = execWith(Map.of("url", base + "/notfound", "onClientError", "error"));

        assertThatThrownBy(() -> delegate.execute(ex))
                .isInstanceOf(BpmnError.class)
                .satisfies(t -> assertThat(((BpmnError) t).getErrorCode()).isEqualTo(HttpCallDelegate.ERR_CLIENT));
        assertThat(resultVar(ex, "httpResult").get("statusCode")).isEqualTo(404);
    }

    @Test
    void clientError_modeIgnore_continuesWithOkFalse() {
        DelegateExecution ex = execWith(Map.of("url", base + "/notfound", "onClientError", "ignore"));
        delegate.execute(ex); // no throw
        assertThat(resultVar(ex, "httpResult").get("ok")).isEqualTo(false);
    }

    @Test
    void serverError_modeRetry_rethrowsForJobRetry() {
        DelegateExecution ex = execWith(Map.of("url", base + "/boom", "onServerError", "retry"));
        assertThatThrownBy(() -> delegate.execute(ex))
                .isInstanceOf(HttpCallDelegate.HttpCallRetryable.class);
    }

    @Test
    void authError_modeError_usesAuthErrorCode() {
        DelegateExecution ex = execWith(Map.of("url", base + "/unauth", "onAuthError", "error"));
        assertThatThrownBy(() -> delegate.execute(ex))
                .isInstanceOf(BpmnError.class)
                .satisfies(t -> assertThat(((BpmnError) t).getErrorCode()).isEqualTo(HttpCallDelegate.ERR_AUTH));
    }

    @Test
    void rateLimit_modeRetry_rethrows() {
        DelegateExecution ex = execWith(Map.of("url", base + "/throttled", "onRateLimit", "retry"));
        assertThatThrownBy(() -> delegate.execute(ex))
                .isInstanceOf(HttpCallDelegate.HttpCallRetryable.class);
    }

    @Test
    void assertion_eqOnBodyField_setsVariable() {
        DelegateExecution ex = execWith(Map.of(
                "url", base + "/obj",
                "assertions", "[{\"path\":\"body.status\",\"equals\":\"ok\",\"setVar\":\"approved\",\"setValue\":true}]"));
        delegate.execute(ex);
        verify(ex).setVariable("approved", Boolean.TRUE);
    }

    @Test
    void assertion_notMatched_doesNotSetVariable() {
        DelegateExecution ex = execWith(Map.of(
                "url", base + "/obj",
                "assertions", "[{\"path\":\"body.status\",\"equals\":\"nope\",\"setVar\":\"approved\",\"setValue\":true}]"));
        delegate.execute(ex);
        org.mockito.Mockito.verify(ex, org.mockito.Mockito.never()).setVariable(eq("approved"), any());
    }

    @Test
    void assertion_numericGtAndListIndexPathsWork() {
        DelegateExecution ex = execWith(Map.of(
                "url", base + "/obj",
                "assertions", "[{\"path\":\"statusCode\",\"op\":\"ge\",\"value\":200,\"setVar\":\"ok2xx\",\"setValue\":\"yes\"},"
                        + "{\"path\":\"body.id\",\"op\":\"gt\",\"value\":5,\"setVar\":\"bigId\",\"setValue\":true}]"));
        delegate.execute(ex);
        verify(ex).setVariable("ok2xx", "yes");
        verify(ex).setVariable("bigId", Boolean.TRUE);
    }

    @Test
    void assertion_existsOnListResponse() {
        DelegateExecution ex = execWith(Map.of(
                "url", base + "/list",
                "assertions", "[{\"path\":\"body.0\",\"op\":\"exists\",\"setVar\":\"hasFirst\",\"setValue\":true}]"));
        delegate.execute(ex);
        verify(ex).setVariable("hasFirst", Boolean.TRUE);
    }

    @Test
    void missingUrl_raisesConfigError() {
        DelegateExecution ex = execWith(Map.of()); // no url
        assertThatThrownBy(() -> delegate.execute(ex))
                .isInstanceOf(BpmnError.class)
                .satisfies(t -> assertThat(((BpmnError) t).getErrorCode()).isEqualTo(HttpCallDelegate.ERR_CONFIG));
    }

    @Test
    void connectionFailure_modeError_raisesTimeoutError() {
        DelegateExecution ex = execWith(Map.of("url", "http://127.0.0.1:1/nope", "onTimeout", "error"));
        assertThatThrownBy(() -> delegate.execute(ex))
                .isInstanceOf(BpmnError.class)
                .satisfies(t -> assertThat(((BpmnError) t).getErrorCode()).isEqualTo(HttpCallDelegate.ERR_TIMEOUT));
    }
}
