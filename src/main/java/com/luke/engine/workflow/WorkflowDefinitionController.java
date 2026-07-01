package com.luke.engine.workflow;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing WORKFLOW design-time API, guarded by the WORKFLOW capability (see
 * {@link com.luke.engine.capability.access.AccessWebConfig}). CRUD on the draft plus
 * the check-in → sign-off → publish lifecycle. Tenant-scoped via {@code X-Tenant-Id};
 * the actor of record is {@code X-User-Id}.
 */
@RestController
@RequestMapping("/api/workflow/definitions")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    public record CreateRequest(String name, String description, String json) {}
    public record UpdateRequest(String name, String description, String json) {}
    public record PublishRequest(int version) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDefinition create(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody CreateRequest body) {
        return service.create(tenantId, body.name(), body.description(), body.json(), userId);
    }

    @GetMapping
    public List<WorkflowDefinition> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public WorkflowDefinition get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return service.get(tenantId, id);
    }

    @GetMapping("/{id}/versions")
    public List<WorkflowVersion> versions(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return service.versions(tenantId, id);
    }

    @PutMapping("/{id}")
    public WorkflowDefinition update(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String id, @RequestBody UpdateRequest body) {
        return service.updateDraft(tenantId, id, body.name(), body.description(), body.json(), userId);
    }

    @PostMapping("/{id}/check-in")
    public WorkflowVersion checkIn(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId, @PathVariable String id) {
        return service.checkIn(tenantId, id, userId);
    }

    @PostMapping("/{id}/versions/{version}/sign-off")
    public WorkflowVersion signOff(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String id, @PathVariable int version) {
        return service.signOff(tenantId, id, version, userId);
    }

    @PostMapping("/{id}/publish")
    public WorkflowDefinition publish(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String id, @RequestBody PublishRequest body) {
        return service.publish(tenantId, id, body.version(), userId);
    }

    public record ErrorResponse(String error) {}

    @ExceptionHandler(WorkflowLifecycleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleLifecycle(WorkflowLifecycleException e) {
        return new ErrorResponse(e.getMessage());
    }
}
