package com.luke.engine.capability.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gatekeeper for capability routes. Reads the caller (X-Tenant-Id + X-User-Id),
 * derives the required level from the HTTP method (GET/HEAD = read, anything
 * else = write), and checks it against {@link CapabilityAccessService}. Rejects
 * with 401 (no identity) or 403 (insufficient level) before the controller runs.
 *
 * <p>The capability a path belongs to is set when the interceptor is registered
 * (e.g. the forms routes → "FORMS"), so one instance guards one capability.
 *
 * <p>Note: identity comes from headers for now; a later phase verifies it from a
 * signed gateway token. The decision logic here does not change then — only the
 * source of the identity does.
 */
@Component
public class CapabilityAccessInterceptor implements HandlerInterceptor {

    private final CapabilityAccessService access;
    private String capabilityCode = "FORMS";

    public CapabilityAccessInterceptor(CapabilityAccessService access) {
        this.access = access;
    }

    public CapabilityAccessInterceptor forCapability(String code) {
        this.capabilityCode = code;
        return this;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true; // CORS preflight

        String tenantId = req.getHeader("X-Tenant-Id");
        String userId = req.getHeader("X-User-Id");
        if (isBlank(tenantId) || isBlank(userId)) {
            return deny(res, HttpServletResponse.SC_UNAUTHORIZED,
                    "X-Tenant-Id and X-User-Id are required");
        }

        boolean needWrite = !isReadMethod(req.getMethod());
        if (!access.isAllowed(tenantId, userId, capabilityCode, needWrite)) {
            String have = access.effectiveLevel(tenantId, userId, capabilityCode);
            return deny(res, HttpServletResponse.SC_FORBIDDEN,
                    "Requires " + capabilityCode + " " + (needWrite ? "read-write" : "read")
                            + (have == null ? " — you have no access" : " — you have " + have));
        }
        return true;
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean deny(HttpServletResponse res, int status, String message) throws Exception {
        // Shared error shape ({error, message, status, correlationId}); Jackson-escaped (#63).
        com.luke.engine.web.ApiError.write(res, status, status == 401 ? "Unauthorized" : "Forbidden", message);
        return false;
    }
}
