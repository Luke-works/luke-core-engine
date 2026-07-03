package com.luke.engine.capability.phone;

import org.springframework.data.jpa.repository.JpaRepository;

/** One {@link PhoneSettings} row per tenant (id == tenantId). */
public interface PhoneSettingsRepository extends JpaRepository<PhoneSettings, String> {
}
