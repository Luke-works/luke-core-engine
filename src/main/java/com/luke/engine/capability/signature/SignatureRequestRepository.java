package com.luke.engine.capability.signature;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped access to {@link SignatureRequest}. Authenticated reads/writes ALWAYS go
 * through a tenant-filtered finder; {@link #findBySignToken} is the only non-tenant entry
 * point (the public signer is authenticated solely by the unguessable token).
 */
public interface SignatureRequestRepository extends JpaRepository<SignatureRequest, String> {

    Optional<SignatureRequest> findByIdAndTenantId(String id, String tenantId);

    List<SignatureRequest> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Public-signing lookup — by the unguessable token, NOT tenant-scoped (the token is the entry). */
    Optional<SignatureRequest> findBySignToken(String signToken);

    /** Code-collision guard for SignatureSupport.generateCode() retries. */
    boolean existsByTenantIdAndCode(String tenantId, String code);

    /** Expired documents eligible for the retention purge (SIG-8). */
    List<SignatureRequest> findByRetainUntilBeforeAndSignedObjectKeyIsNotNull(LocalDateTime cutoff);
}
