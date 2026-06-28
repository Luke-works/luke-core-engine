package com.luke.engine.capability.signature;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Recipients of a {@link SignatureInstance}; {@code findBySignToken} is the public signing entry. */
public interface SignatureRecipientRepository extends JpaRepository<SignatureRecipient, String> {

    List<SignatureRecipient> findByInstanceIdOrderBySigningOrderAsc(String instanceId);

    Optional<SignatureRecipient> findBySignToken(String signToken);

    void deleteByInstanceId(String instanceId);
}
