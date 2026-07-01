package com.luke.engine.workflow;

import com.luke.engine.workflow.WorkflowRunService.RunDetail;
import com.luke.engine.workflow.WorkflowRunService.RunSummary;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
 * The WORKFLOW runtime API (Pillar 4): start a test run of a published workflow and
 * inspect its runs. Guarded by the WORKFLOW capability (see
 * {@link com.luke.engine.capability.access.AccessWebConfig}); tenant-scoped via
 * {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowRunController {

    private final WorkflowRunService runs;

    public WorkflowRunController(WorkflowRunService runs) {
        this.runs = runs;
    }

    public record StartRequest(Map<String, Object> variables) {}
    public record StartResponse(String instanceId) {}
    public record ErrorResponse(String error) {}

    /** Start a test instance of the definition's published version. */
    @PostMapping("/definitions/{id}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public StartResponse start(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String id, @RequestBody(required = false) StartRequest body) {
        Map<String, Object> variables = body != null ? body.variables() : null;
        return new StartResponse(runs.startTestRun(tenantId, id, variables, userId));
    }

    /** Recent runs for the definition (running + finished). */
    @GetMapping("/definitions/{id}/runs")
    public List<RunSummary> list(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return runs.listRuns(tenantId, id, 25);
    }

    /** Full status of one run — state, current activities, active tasks, incidents. */
    @GetMapping("/runs/{instanceId}")
    public RunDetail detail(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String instanceId) {
        return runs.runDetail(tenantId, instanceId);
    }

    @ExceptionHandler(WorkflowLifecycleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleLifecycle(WorkflowLifecycleException e) {
        return new ErrorResponse(e.getMessage());
    }
}
