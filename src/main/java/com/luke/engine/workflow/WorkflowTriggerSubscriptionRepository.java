package com.luke.engine.workflow;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The start-on-event registry: matched by the correlator, replaced per definition at publish. */
public interface WorkflowTriggerSubscriptionRepository extends JpaRepository<WorkflowTriggerSubscription, String> {

    /** All subscriptions for a tenant's capability+event (formCode filtering is done in-memory). */
    List<WorkflowTriggerSubscription> findByTenantIdAndCapabilityAndEventType(
            String tenantId, String capability, String eventType);

    /** Remove any existing subscription(s) for a definition (called before re-inserting at publish). */
    void deleteByDefinitionId(String definitionId);
}
