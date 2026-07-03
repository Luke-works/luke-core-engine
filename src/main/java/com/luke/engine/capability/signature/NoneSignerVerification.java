package com.luke.engine.capability.signature;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V1 default {@link SignerVerification}: no verification — anyone with the unguessable sign
 * token can sign. Active unless {@code luke.sign.verification.provider} selects another impl
 * (SIG-9 introduces {@code EmailOtpSignerVerification} and routing by
 * {@link VerificationMethod}).
 */
@Component
@ConditionalOnProperty(name = "luke.sign.verification.provider", havingValue = "none", matchIfMissing = true)
public class NoneSignerVerification implements SignerVerification {

    @Override
    public boolean required(SignatureRequest request) {
        return false;
    }

    @Override
    public void challenge(SignatureRequest request) {
        // no-op
    }

    @Override
    public boolean verify(SignatureRequest request, String code) {
        return true;
    }
}
