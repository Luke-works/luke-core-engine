package com.luke.engine.emailasset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Access to {@link EmailAsset}. Authenticated finders are tenant-scoped (tenantId first). The public
 * serve path resolves by id ALONE ({@link JpaRepository#findById}) — the unguessable assetId is the
 * bearer, and only READY assets are served (enforced in the service).
 */
public interface EmailAssetRepository extends JpaRepository<EmailAsset, String> {

    Optional<EmailAsset> findByIdAndTenantId(String id, String tenantId);

    List<EmailAsset> findByTenantIdAndTemplateIdOrderByCreatedAtDesc(String tenantId, String templateId);
}
