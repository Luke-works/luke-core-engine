package com.luke.engine.capability.form;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormEmbedSiteRepository extends JpaRepository<FormEmbedSite, String> {

    Optional<FormEmbedSite> findByTenantIdAndFormCodeAndOrigin(String tenantId, String formCode, String origin);

    List<FormEmbedSite> findByTenantIdAndFormCodeOrderByLastSeenAtDesc(String tenantId, String formCode);

    long countByTenantIdAndFormCode(String tenantId, String formCode);
}
