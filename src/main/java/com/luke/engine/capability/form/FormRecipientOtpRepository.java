package com.luke.engine.capability.form;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormRecipientOtpRepository extends JpaRepository<FormRecipientOtp, String> {

    Optional<FormRecipientOtp> findByInstanceId(String instanceId);
}
