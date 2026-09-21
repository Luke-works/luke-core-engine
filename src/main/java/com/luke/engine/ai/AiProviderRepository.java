package com.luke.engine.ai;

import org.springframework.data.jpa.repository.JpaRepository;

/** One connected AI provider per tenant — the id IS the tenantId. */
public interface AiProviderRepository extends JpaRepository<AiProvider, String> {
}
