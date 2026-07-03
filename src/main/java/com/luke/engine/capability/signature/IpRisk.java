package com.luke.engine.capability.signature;

/**
 * Risk classification of a client IP, recorded on every public-endpoint audit event
 * by {@link IpReputationProvider}. Stored as a string (@Enumerated STRING) — DB-portable.
 *
 * <p>{@code CLEAN} = no signal. {@code TOR} / {@code DATACENTER} are detectable for free
 * (Tor exit list + hosting-ASN heuristic). {@code VPN} / {@code PROXY} / {@code RELAY}
 * need a paid feed ({@code PaidIpReputationProvider}). RELAY (e.g. iCloud Private Relay)
 * is legitimate and should NOT be blocked by default.
 */
public enum IpRisk {
    CLEAN,
    TOR,
    DATACENTER,
    VPN,
    PROXY,
    RELAY
}
