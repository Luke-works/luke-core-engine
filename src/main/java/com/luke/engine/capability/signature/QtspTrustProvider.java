package com.luke.engine.capability.signature;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SEAM (not built in V1). Selected by {@code LUKE_SIGN_TRUST_MODE=qtsp}. A real implementation
 * delegates each signature to a Qualified Trust Service Provider to produce an EU eIDAS QES/AdES
 * seal — candidate APIs: Swisscom AIS, eID Easy, D-Trust sign-me. This is a CONFIG SWAP from
 * the {@link SelfSealTrustProvider} default; no other code changes.
 */
@Component
@ConditionalOnProperty(name = "luke.sign.trust.mode", havingValue = "qtsp")
public class QtspTrustProvider implements TrustProvider {

    @Override
    public byte[] seal(byte[] preparedPdf, SealMeta meta) {
        throw new UnsupportedOperationException(
                "QTSP not configured — implement against a QTSP API (Swisscom AIS / eID Easy / "
                        + "D-Trust sign-me) for EU eIDAS QES. Post-V1 seam.");
    }
}
