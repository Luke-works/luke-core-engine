package com.luke.engine.capability.email;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailBoxRepository extends JpaRepository<EmailBox, String> {

    List<EmailBox> findByTenantIdOrderByCreatedAtAsc(String tenantId);

    List<EmailBox> findByTenantIdAndDirection(String tenantId, String direction);

    Optional<EmailBox> findByIdAndTenantId(String id, String tenantId);

    boolean existsByTenantIdAndDirectionAndAddress(String tenantId, String direction, String address);

    /** INBOUND routing: the box a Postmark inbound message maps to, by exact recipient address. */
    Optional<EmailBox> findByTenantIdAndDirectionAndAddress(String tenantId, String direction, String address);

    /** INBOUND routing: fallback match by MailboxHash ({@code +hash@inbound.postmarkapp.com}). */
    Optional<EmailBox> findByTenantIdAndDirectionAndRoutingKey(String tenantId, String direction, String routingKey);
}
