package com.luke.engine.capability.signature;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The IP-stamped audit trail for signature requests. Ordered oldest-first so the
 * Certificate of Completion (SIG-2) and the UI history view (SIG-4) read top-to-bottom.
 */
public interface SignatureAuditEventRepository extends JpaRepository<SignatureAuditEvent, String> {

    /** Full trail for one request (requestId already implies a single tenant). */
    List<SignatureAuditEvent> findByRequestIdOrderByAtAsc(String requestId);

    /** Tenant-guarded variant for authed read paths. */
    List<SignatureAuditEvent> findByRequestIdAndTenantIdOrderByAtAsc(String requestId, String tenantId);
}
