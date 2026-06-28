package com.luke.engine.capability.signature;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Immutable version snapshots for a {@link SignatureDefinition}. */
public interface SignatureVersionRepository extends JpaRepository<SignatureVersion, String> {

    List<SignatureVersion> findByDefinitionIdOrderByVersionAsc(String definitionId);

    Optional<SignatureVersion> findByDefinitionIdAndVersion(String definitionId, int version);

    Optional<SignatureVersion> findTopByDefinitionIdOrderByVersionDesc(String definitionId);

    void deleteByDefinitionId(String definitionId);
}
