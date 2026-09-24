package com.luke.engine.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** A workspace may connect several providers — one row each, keyed (tenantId, provider). */
public interface AiProviderRepository extends JpaRepository<AiProvider, String> {

    List<AiProvider> findByTenantIdOrderByProviderAsc(String tenantId);

    Optional<AiProvider> findByTenantIdAndProvider(String tenantId, String provider);

    List<AiProvider> findByTenantIdAndStatus(String tenantId, String status);

    @Modifying
    @Transactional
    @Query("delete from AiProvider p where p.tenantId = :tenantId")
    void deleteByTenant(@Param("tenantId") String tenantId);
}
