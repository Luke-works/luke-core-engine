package com.luke.engine.usage;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-serve, read-only usage for the CURRENT tenant:
 *
 * <pre>
 *   GET /api/usage → { plan, period, usage: { submissions: {used, limit}, emails: {used, limit} } }
 * </pre>
 *
 * <p>Reads the caller's own tenant from {@code X-Tenant-Id} (mirrors {@code GET /api/plan}); no
 * operator gate — seeing your own usage is not privileged. {@code limit} is {@code null} for an
 * unlimited (Enterprise) tier.
 */
@RestController
@RequestMapping("/api")
public class UsageController {

    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/usage")
    public Map<String, Object> myUsage(@RequestHeader(name = "X-Tenant-Id", required = false) String tenantId) {
        return usage.snapshot(tenantId);
    }
}
