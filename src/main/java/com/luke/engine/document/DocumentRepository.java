package com.luke.engine.document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped access to {@link Document}. Every authenticated finder is tenant-filtered (mirrors
 * {@code SignatureRequestRepository}) — there is no non-tenant entry point, since documents are
 * always reached through the tenant + capability + task/group authZ layers (DOC-4).
 */
public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByIdAndTenantId(String id, String tenantId);

    /** Idempotency key for registering an already-stored blob (DOC-7). */
    Optional<Document> findByTenantIdAndStorageKey(String tenantId, String storageKey);

    /** A process's whole case file (process- and task-level attachments), newest first. */
    List<Document> findByTenantIdAndProcessRefOrderByCreatedAtDesc(String tenantId, String processRef);

    List<Document> findByTenantIdAndProcessRefAndKindOrderByCreatedAtDesc(String tenantId, String processRef, String kind);

    /** One task's attachments. */
    List<Document> findByTenantIdAndTaskIdOrderByCreatedAtDesc(String tenantId, String taskId);

    /** Attachments owned by a capability entity (e.g. a signature request / form instance). */
    List<Document> findByTenantIdAndCapabilityAndOwnerEntityIdOrderByCreatedAtDesc(
            String tenantId, String capability, String ownerEntityId);

    /** Flow-A backfill (DOC-9): stamp processInstanceId onto every row of a processRef. */
    List<Document> findByTenantIdAndProcessRef(String tenantId, String processRef);

    /** Expired documents eligible for the retention purge (DOC-5). */
    List<Document> findByRetainUntilBeforeAndStatusNot(LocalDateTime cutoff, String status);
}
