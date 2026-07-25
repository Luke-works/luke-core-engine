package com.luke.engine.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Access to {@link AuditEvent}. Deliberately extends the bare {@link Repository} marker (NOT
 * {@code JpaRepository}) and declares ONLY {@code save} + paged reads — so the audit trail is
 * append-only at the interface level: no code path can update or delete an event.
 *
 * <p>Reads are newest-first and paginated. The operator sees any tenant / the cross-tenant view;
 * a tenant-admin is scoped to their own tenant (enforced in {@code AuditController}).
 */
public interface AuditEventRepository extends Repository<AuditEvent, String> {

    AuditEvent save(AuditEvent event);

    Page<AuditEvent> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Page<AuditEvent> findByTenantIdAndActionOrderByCreatedAtDesc(String tenantId, String action, Pageable pageable);

    Page<AuditEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditEvent> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
}
