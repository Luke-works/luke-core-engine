package com.luke.engine.capability.capability;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapabilityRepository extends JpaRepository<Capability, String> {

    List<Capability> findAllByStatus(String status);

    Optional<Capability> findByCode(String code);

    boolean existsByCode(String code);
}
