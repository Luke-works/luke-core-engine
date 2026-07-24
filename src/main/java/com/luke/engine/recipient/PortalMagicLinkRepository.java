package com.luke.engine.recipient;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PortalMagicLinkRepository extends JpaRepository<PortalMagicLink, String> {

    /** Consume path: resolve a link by the SHA-256 of its raw token. */
    Optional<PortalMagicLink> findByTokenHash(String tokenHash);

    /** Most-recent link for a recipient — used to throttle re-requests before issuing a fresh one. */
    Optional<PortalMagicLink> findFirstByTenantIdAndRecipientEmailOrderByCreatedAtDesc(
            String tenantId, String recipientEmail);

    /** Clears any prior links for a recipient before a fresh one is issued (one active per pair). */
    @Transactional
    void deleteByTenantIdAndRecipientEmail(String tenantId, String recipientEmail);

    /** Atomic single-use claim: flips consumedAt only if still unconsumed. Returns rows affected
     *  (1 = this caller won the race, 0 = already consumed) so concurrent consumes can't double-mint. */
    @Modifying
    @Query("update PortalMagicLink m set m.consumedAt = :now where m.id = :id and m.consumedAt is null")
    int markConsumed(@Param("id") String id, @Param("now") LocalDateTime now);
}
