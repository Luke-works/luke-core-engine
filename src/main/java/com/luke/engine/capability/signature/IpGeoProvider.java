package com.luke.engine.capability.signature;

/**
 * SPI for OPTIONAL IP geolocation on audit events. OFF by default ({@link NoOpIpGeoProvider}).
 * If enabled later, use a permissively-licensed source (DB-IP Lite = CC-BY, attribution) or a
 * paid API — avoid CC-BY-SA/copyleft geo DBs. IP + geo are personal data (GDPR), retained as a
 * legal record.
 */
public interface IpGeoProvider {

    /** Best-effort lookup; returns null when geo is disabled or unresolved. */
    Geo lookup(String ip);

    record Geo(String country, String city) {}
}
