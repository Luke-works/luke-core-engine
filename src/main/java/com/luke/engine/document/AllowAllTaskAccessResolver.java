package com.luke.engine.document;

/**
 * V1 default {@link TaskAccessResolver}: the context layer is a no-op (allow) — tenant + capability
 * gating still fully apply. Registered as a fallback bean by {@link DocumentConfig}; DOC-4 replaces it
 * with the Camunda candidate-group resolver and this default backs off.
 */
public class AllowAllTaskAccessResolver implements TaskAccessResolver {

    @Override
    public boolean canAccess(String tenantId, String userId, Document doc) {
        return true;
    }

    @Override
    public boolean canUpload(String tenantId, String userId, String processRef,
                             String processInstanceId, String taskId) {
        return true;
    }
}
