package com.luke.engine.workflow;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The WORKFLOW step-type catalog API. The builder fetches this to render its
 * palette and auto-generate node config forms; the set is filtered client-side by
 * the tenant's subscribed capabilities.
 *
 * <p>Guarded by the WORKFLOW capability (see
 * {@link com.luke.engine.capability.access.AccessWebConfig}); tenant-scoped via
 * {@code X-Tenant-Id} at the gateway.
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowCatalogController {

    private final StepTypeRegistry registry;

    public WorkflowCatalogController(StepTypeRegistry registry) {
        this.registry = registry;
    }

    /** The aggregated step-type catalog. */
    public record Catalog(List<StepTypeDescriptor> steps) {}

    /** {@code GET /api/workflow/catalog} — all registered step types. */
    @GetMapping("/catalog")
    public Catalog catalog() {
        return new Catalog(registry.all());
    }
}
