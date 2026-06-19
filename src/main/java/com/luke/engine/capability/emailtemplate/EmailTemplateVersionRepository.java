package com.luke.engine.capability.emailtemplate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailTemplateVersionRepository extends JpaRepository<EmailTemplateVersion, String> {

    List<EmailTemplateVersion> findByEmailTemplateIdOrderByVersionAsc(String emailTemplateId);

    Optional<EmailTemplateVersion> findByEmailTemplateIdAndVersion(String emailTemplateId, int version);

    Optional<EmailTemplateVersion> findTopByEmailTemplateIdOrderByVersionDesc(String emailTemplateId);
}
