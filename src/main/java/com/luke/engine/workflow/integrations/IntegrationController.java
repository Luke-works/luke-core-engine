package com.luke.engine.workflow.integrations;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing integrations API, guarded by the WORKFLOW capability (see
 * {@link com.luke.engine.capability.access.AccessWebConfig}). Starts connect flows and
 * manages connections; the OAuth itself happens in Nango's Connect UI in the browser
 * using the returned session token. Tenant-scoped via {@code X-Tenant-Id}; actor is
 * {@code X-User-Id}.
 */
@RestController
@RequestMapping("/api/workflow/integrations")
public class IntegrationController {

    private final ConnectionService service;

    public IntegrationController(ConnectionService service) {
        this.service = service;
    }

    public record ConnectRequest(String userEmail) {}

    /** Start a connect flow for {@code provider}; returns the connection id + Nango session token. */
    @PostMapping("/{provider}/connect")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionService.ConnectResult connect(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String provider,
            @RequestBody(required = false) ConnectRequest body) {
        String email = body != null ? body.userEmail() : null;
        return service.startConnect(tenantId, provider, userId, email);
    }

    @GetMapping("/connections")
    public List<IntegrationConnection> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/connections/{id}")
    public IntegrationConnection get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return service.get(tenantId, id);
    }

    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        service.disconnect(tenantId, id);
    }

    public record ErrorResponse(String error) {}

    @ExceptionHandler(QuotaExceededException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleQuota(QuotaExceededException e) {
        return new ErrorResponse(e.getMessage());
    }

    @ExceptionHandler(IntegrationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIntegration(IntegrationException e) {
        return new ErrorResponse(e.getMessage());
    }
}
