package com.luke.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.capability.signature.ClientIp;
import com.luke.engine.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records privileged admin actions to the durable {@link AuditEvent} trail (#37).
 *
 * <p>Two guarantees:
 * <ul>
 *   <li><b>Fail-soft.</b> An audit-store failure must NEVER break the privileged action it records
 *       (default-lenient) — a broken/absent {@code luke_audit_event} table degrades to log-only, it
 *       does not 500 the admin operation.
 *   <li><b>Never lost.</b> Every event is ALSO emitted to the {@code luke.audit} SLF4J logger before
 *       the DB write, so the record survives even when the store is unavailable — a tamper-evident
 *       backstop mirroring the stateless auth-engine's audit logger.
 * </ul>
 *
 * <p>The {@code source} of the action (client IP, HTTP method + path) is pulled from the in-flight
 * request via {@link RequestContextHolder}, so call sites don't have to thread an
 * {@code HttpServletRequest} through every handler signature.
 */
@Service
public class AdminAuditService {

    /** Dedicated, filterable audit channel (route to its own appender in prod). */
    private static final Logger AUDIT = LoggerFactory.getLogger("luke.audit");
    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private static final int DETAIL_MAX = 4000;

    private final AuditEventRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminAuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String targetType, String targetId,
                       String tenantId, String actorId, boolean operator) {
        record(action, targetType, targetId, tenantId, actorId, operator, null);
    }

    /**
     * Record one privileged admin mutation. {@code detail} is optional free-form context
     * (serialized to JSON). Never throws — a persistence failure is logged, not propagated.
     */
    public void record(String action, String targetType, String targetId,
                       String tenantId, String actorId, boolean operator, Map<String, Object> detail) {
        AuditEvent e = new AuditEvent();
        e.setAction(action);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setTenantId(tenantId);
        e.setActorId(actorId);
        e.setActorOperator(operator);

        HttpServletRequest req = currentRequest();
        if (req != null) {
            e.setSourceIp(ClientIp.resolve(req));
            e.setRequestMethod(req.getMethod());
            e.setRequestPath(req.getRequestURI());
        }
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null && !cid.isBlank()) {
            e.setCorrelationId(cid);
        }
        String detailJson = null;
        if (detail != null && !detail.isEmpty()) {
            detailJson = toJson(detail);
            e.setDetail(clip(detailJson));
        }

        // Backstop FIRST: this line survives even if the DB write below fails.
        AUDIT.info("action={} actor={} operator={} tenant={} target={}:{} ip={} method={} path={} cid={} detail={}",
                action, actorId, operator, tenantId, targetType, targetId,
                e.getSourceIp(), e.getRequestMethod(), e.getRequestPath(), e.getCorrelationId(), detailJson);

        try {
            repository.save(e);
        } catch (Exception ex) {
            // Never let an audit-store failure break the action it records — the luke.audit line above
            // is the durable record until the store recovers.
            log.error("AUDIT PERSIST FAILED action={} actor={} tenant={} target={}:{} — recorded to luke.audit only",
                    action, actorId, tenantId, targetType, targetId, ex);
        }
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return mapper.writeValueAsString(detail);
        } catch (Exception ex) {
            return String.valueOf(detail);
        }
    }

    private static String clip(String s) {
        return (s != null && s.length() > DETAIL_MAX) ? s.substring(0, DETAIL_MAX) : s;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return (attrs instanceof ServletRequestAttributes sra) ? sra.getRequest() : null;
    }
}
