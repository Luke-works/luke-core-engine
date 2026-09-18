package com.luke.engine.payments;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentConnectStateRepository extends JpaRepository<PaymentConnectState, String> {

    @Modifying
    @Transactional
    @Query("delete from PaymentConnectState s where s.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query("delete from PaymentConnectState s where s.tenantId = :tenantId")
    int deleteByTenant(@Param("tenantId") String tenantId);
}
