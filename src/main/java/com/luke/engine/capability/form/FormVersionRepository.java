package com.luke.engine.capability.form;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormVersionRepository extends JpaRepository<FormVersion, String> {

    List<FormVersion> findByFormIdOrderByVersionAsc(String formId);

    Optional<FormVersion> findByFormIdAndVersion(String formId, int version);

    Optional<FormVersion> findTopByFormIdOrderByVersionDesc(String formId);
}
