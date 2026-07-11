package com.luke.engine.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.finos.fluxnova.bpm.engine.IdentityService;
import org.finos.fluxnova.bpm.engine.identity.TenantQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** #32: internal process-start must reject wrong keys and unknown tenants. */
class InternalProcessControllerTest {

    private InternalProcessService processService;
    private IdentityService identityService;
    private TenantQuery tenantQuery;
    private InternalProcessController controller;

    @BeforeEach
    void setUp() {
        processService = mock(InternalProcessService.class);
        identityService = mock(IdentityService.class);
        tenantQuery = mock(TenantQuery.class);
        when(identityService.createTenantQuery()).thenReturn(tenantQuery);
        when(tenantQuery.tenantId(anyString())).thenReturn(tenantQuery);
        controller = new InternalProcessController(processService, identityService);
        ReflectionTestUtils.setField(controller, "sharedSecret", "s3cret");
    }

    private InternalProcessController.StartBody body(String tenant) {
        return new InternalProcessController.StartBody(tenant, "bk-1", null);
    }

    @Test
    void wrongKeyIsForbidden() {
        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> controller.start("wrong", body("t-a")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(processService, never()).start(any(), any(), any());
    }

    @Test
    void unknownTenantIsNotFound() {
        when(tenantQuery.count()).thenReturn(0L);
        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> controller.start("s3cret", body("ghost")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(processService, never()).start(any(), any(), any());
    }

    @Test
    void validKeyAndKnownTenantStarts() {
        when(tenantQuery.count()).thenReturn(1L);
        when(processService.start(anyString(), anyString(), any())).thenReturn("pid-99");
        Object result = controller.start("s3cret", body("t-a")).get("processInstanceId");
        assertEquals("pid-99", result);
    }
}
