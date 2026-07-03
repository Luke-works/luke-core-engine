package com.luke.engine.workflow.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ConnectionService} (WF-8), with the repository and Nango client
 * mocked. Covers the connect happy path, the quota gate, disconnect (revoke + delete),
 * and the webhook-driven activate transition.
 */
class ConnectionServiceTest {

    private static final String TENANT = "t1";

    private final IntegrationConnectionRepository repo = mock(IntegrationConnectionRepository.class);
    private final NangoClient nango = mock(NangoClient.class);
    private ConnectionService service;

    @BeforeEach
    void setUp() {
        service = new ConnectionService(repo, nango);
        ReflectionTestUtils.setField(service, "maxConnectionsPerTenant", 3);
    }

    @Test
    void startConnectCreatesPendingRowAndReturnsSessionToken() {
        when(repo.countByTenantIdAndStatusNot(TENANT, IntegrationConnectionStatus.REVOKED)).thenReturn(0L);
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        when(nango.createConnectSession(eq("salesforce"), anyString(), eq("u@x.com"), eq(TENANT)))
                .thenReturn(new NangoClient.ConnectSession("tok_123", "2026-07-01T00:00:00Z"));

        ConnectionService.ConnectResult r = service.startConnect(TENANT, "salesforce", "user1", "u@x.com");

        assertThat(r.sessionToken()).isEqualTo("tok_123");
        assertThat(r.connectionId()).isNotBlank();

        ArgumentCaptor<IntegrationConnection> saved = ArgumentCaptor.forClass(IntegrationConnection.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IntegrationConnectionStatus.PENDING);
        assertThat(saved.getValue().getProviderKey()).isEqualTo("salesforce");
        // the row id is passed to Nango as the end-user reference for webhook correlation
        assertThat(saved.getValue().getId()).isEqualTo(r.connectionId());
    }

    @Test
    void startConnectEnforcesTheQuotaAndDoesNotCallNango() {
        when(repo.countByTenantIdAndStatusNot(TENANT, IntegrationConnectionStatus.REVOKED)).thenReturn(3L);

        assertThatThrownBy(() -> service.startConnect(TENANT, "slack", "user1", null))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("quota");

        verify(repo, never()).save(any());
        verify(nango, never()).createConnectSession(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void disconnectRevokesInNangoAndDeletesTheRow() {
        IntegrationConnection conn = new IntegrationConnection("c1", TENANT, "salesforce", "user1");
        conn.setNangoConnectionId("nango_abc");
        when(repo.findByIdAndTenantId("c1", TENANT)).thenReturn(Optional.of(conn));

        service.disconnect(TENANT, "c1");

        verify(nango).deleteConnection("salesforce", "nango_abc");
        verify(repo).delete(conn);
    }

    @Test
    void markActiveFlipsStatusAndRecordsNangoHandle() {
        IntegrationConnection conn = new IntegrationConnection("c1", TENANT, "salesforce", "user1");
        when(repo.findById("c1")).thenReturn(Optional.of(conn));
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));

        IntegrationConnection out = service.markActive("c1", "nango_abc", "Acme Corp", "read,write");

        assertThat(out.getStatus()).isEqualTo(IntegrationConnectionStatus.ACTIVE);
        assertThat(out.getNangoConnectionId()).isEqualTo("nango_abc");
        assertThat(out.getExternalAccount()).isEqualTo("Acme Corp");
    }
}
