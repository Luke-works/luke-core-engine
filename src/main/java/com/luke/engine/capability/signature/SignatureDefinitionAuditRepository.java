package com.luke.engine.capability.signature;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only design-time audit trail for {@link SignatureDefinition}. */
public interface SignatureDefinitionAuditRepository extends JpaRepository<SignatureDefinitionAuditEvent, String> {

    List<SignatureDefinitionAuditEvent> findByDefinitionIdOrderByAtAsc(String definitionId);

    void deleteByDefinitionId(String definitionId);
}
