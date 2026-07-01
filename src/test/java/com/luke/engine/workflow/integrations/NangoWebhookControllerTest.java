package com.luke.engine.workflow.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for {@link NangoWebhookController}: auth-success → ACTIVE, dedup, and fail-closed
 * signature rejection. Verifier is real (lenient by default); service + log repo mocked.
 */
class NangoWebhookControllerTest {

    private final ConnectionService connections = mock(ConnectionService.class);
    private final IntegrationConnectionRepository connectionRepo = mock(IntegrationConnectionRepository.class);
    private final IntegrationEventOutboxRepository eventOutbox = mock(IntegrationEventOutboxRepository.class);
    private final IntegrationWebhookLogRepository log = mock(IntegrationWebhookLogRepository.class);
    private final NangoWebhookVerifier verifier = new NangoWebhookVerifier(); // lenient: no secret
    private final NangoWebhookController controller =
            new NangoWebhookController(verifier, connections, connectionRepo, eventOutbox, log, new ObjectMapper());

    private static final String AUTH_OK =
            "{ \"type\": \"auth\", \"success\": true, \"connectionId\": \"nango_1\", "
            + "\"providerConfigKey\": \"salesforce\", \"endUser\": { \"endUserId\": \"row1\" } }";

    @Test
    void authSuccessActivatesTheCorrelatedConnection() {
        when(log.existsById(anyString())).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.webhook(null, AUTH_OK);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(connections).markActive(eq("row1"), eq("nango_1"), any(), any());
        verify(log).save(any(IntegrationWebhookLog.class));
    }

    @Test
    void duplicateDeliveryIsSkipped() {
        when(log.existsById(anyString())).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.webhook(null, AUTH_OK);

        assertThat(resp.getBody()).containsEntry("status", "duplicate");
        verify(connections, never()).markActive(anyString(), anyString(), any(), any());
        verify(log, never()).save(any());
    }

    @Test
    void authFailureFlagsNeedsReconnect() {
        when(log.existsById(anyString())).thenReturn(false);
        String authFail = AUTH_OK.replace("\"success\": true", "\"success\": false");

        controller.webhook(null, authFail);

        verify(connections).markNeedsReconnect(eq("row1"), anyString());
        verify(connections, never()).markActive(anyString(), anyString(), any(), any());
    }

    @Test
    void syncEventEnqueuesAnOutboxRowForCorrelation() {
        when(log.existsById(anyString())).thenReturn(false);
        IntegrationConnection conn = new IntegrationConnection("row1", "t1", "salesforce", "u");
        conn.setNangoConnectionId("nango_1");
        when(connectionRepo.findFirstByNangoConnectionId("nango_1")).thenReturn(java.util.Optional.of(conn));

        String syncEvent =
                "{ \"type\": \"sync\", \"connectionId\": \"nango_1\", \"providerConfigKey\": \"salesforce\", "
                + "\"model\": \"Opportunity\" }";
        controller.webhook(null, syncEvent);

        org.mockito.ArgumentCaptor<IntegrationEventOutbox> row =
                org.mockito.ArgumentCaptor.forClass(IntegrationEventOutbox.class);
        verify(eventOutbox).save(row.capture());
        assertThat(row.getValue().getTenantId()).isEqualTo("t1");
        assertThat(row.getValue().getMessageName()).isEqualTo("integrations.Opportunity");
        assertThat(row.getValue().getState()).isEqualTo(IntegrationEventOutbox.QUEUED);
    }

    @Test
    void syncEventForUnknownConnectionIsDropped() {
        when(log.existsById(anyString())).thenReturn(false);
        when(connectionRepo.findFirstByNangoConnectionId(anyString())).thenReturn(java.util.Optional.empty());

        controller.webhook(null, "{ \"type\": \"sync\", \"connectionId\": \"ghost\", \"model\": \"X\" }");

        verify(eventOutbox, never()).save(any());
    }

    @Test
    void rejectsAnInvalidSignatureWhenRequired() {
        ReflectionTestUtils.setField(verifier, "secretKey", "sekret");
        ReflectionTestUtils.setField(verifier, "requireSignature", true);

        ResponseEntity<Map<String, Object>> resp = controller.webhook("badsig", AUTH_OK);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(connections, never()).markActive(anyString(), anyString(), any(), any());
    }
}
