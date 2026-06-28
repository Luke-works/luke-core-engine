package com.luke.engine.capability.signature;

import org.springframework.stereotype.Component;

/**
 * Default {@link IpGeoProvider}: geolocation OFF (returns null). To enable geo, add a real
 * provider (DB-IP Lite / a paid API) and make it the active bean (e.g. {@code @Primary} or a
 * {@code @ConditionalOnProperty} pair) — then remove or disable this one.
 */
@Component
public class NoOpIpGeoProvider implements IpGeoProvider {

    @Override
    public Geo lookup(String ip) {
        return null;
    }
}
