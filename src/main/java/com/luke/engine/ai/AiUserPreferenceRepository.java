package com.luke.engine.ai;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AiUserPreferenceRepository extends JpaRepository<AiUserPreference, String> {

    Optional<AiUserPreference> findByTenantIdAndUserId(String tenantId, String userId);

    /** A purged tenant takes its members' model choices with it. */
    @Modifying
    @Transactional
    @Query("delete from AiUserPreference p where p.tenantId = :tenantId")
    void deleteByTenant(@Param("tenantId") String tenantId);

    /** A deprovisioned user takes their choices in every workspace with them. */
    @Modifying
    @Transactional
    @Query("delete from AiUserPreference p where p.userId = :userId")
    void deleteByUser(@Param("userId") String userId);
}
