package com.luke.engine.capability.signature;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SEAM (not built in V1). Activated by {@code luke.sign.ip.reputation-provider=paid}. A real
 * implementation calls a paid IP-intelligence feed — IPQualityScore, IPinfo, or
 * IPGeolocation.io — to detect VPN / residential-proxy / RELAY (which the free Tor+ASN
 * default cannot). Wire the API key + map the vendor's response to {@link IpRisk}.
 */
@Component
@ConditionalOnProperty(name = "luke.sign.ip.reputation-provider", havingValue = "paid")
public class PaidIpReputationProvider implements IpReputationProvider {

    @Override
    public IpRisk classify(String ip) {
        throw new UnsupportedOperationException(
                "Configure a paid IP reputation feed (IPQS / IPinfo / IPGeolocation.io) — post-V1 seam");
    }
}
