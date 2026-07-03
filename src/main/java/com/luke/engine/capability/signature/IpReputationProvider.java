package com.luke.engine.capability.signature;

/**
 * SPI that classifies a client IP's risk ({@link IpRisk}) for the audit trail and the
 * (opt-in) blocking policy. Default {@link FreeIpReputationProvider} detects TOR + datacenter
 * for free; {@code PaidIpReputationProvider} (seam) adds real VPN/residential-proxy/relay
 * detection via a paid feed. Selected by {@code luke.sign.ip.reputation-provider=free|paid}.
 *
 * <p>Policy reminder: classification is FLAG-by-default — blocking is opt-in
 * ({@code LUKE_SIGN_BLOCK_IP_RISK}) and enforced in the public controller (SIG-3), never here.
 */
public interface IpReputationProvider {

    IpRisk classify(String ip);
}
