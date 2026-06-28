package com.luke.engine.capability.signature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default (free, partial) {@link IpReputationProvider}: flags <b>Tor</b> exit nodes from the
 * public bulk exit list (lazily fetched + cached, refreshed on a TTL, fully best-effort) and
 * leaves a hook for a datacenter/hosting-ASN heuristic. Everything else → {@code CLEAN}.
 *
 * <p>This is intentionally partial — reliable VPN / residential-proxy / RELAY detection needs
 * a PAID feed (IPQS / IPinfo / IPGeolocation.io); see {@link PaidIpReputationProvider}. No
 * network call happens at startup; the list loads on first {@link #classify} (i.e. on the
 * first public sign request) and any fetch failure degrades to CLEAN.
 */
@Component
@ConditionalOnProperty(name = "luke.sign.ip.reputation-provider", havingValue = "free", matchIfMissing = true)
public class FreeIpReputationProvider implements IpReputationProvider {

    private static final Logger log = LoggerFactory.getLogger(FreeIpReputationProvider.class);

    private final String torListUrl;
    private final boolean torEnabled;
    private final long refreshMillis;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile Set<String> torExits = Set.of();
    private volatile long lastLoadAttempt = 0L;
    private final Object loadLock = new Object();

    public FreeIpReputationProvider(
            @Value("${luke.sign.ip.tor-list-url:https://check.torproject.org/torbulkexitlist}") String torListUrl,
            @Value("${luke.sign.ip.tor-list-enabled:true}") boolean torEnabled,
            @Value("${luke.sign.ip.tor-refresh-minutes:360}") long refreshMinutes) {
        this.torListUrl = torListUrl;
        this.torEnabled = torEnabled;
        this.refreshMillis = Math.max(1, refreshMinutes) * 60_000L;
    }

    @Override
    public IpRisk classify(String ip) {
        if (ip == null || ip.isBlank()) return IpRisk.CLEAN;
        if (torEnabled) {
            maybeRefresh();
            if (torExits.contains(ip)) return IpRisk.TOR;
        }
        // Datacenter/hosting-ASN heuristic needs an ASN DB; without a paid feed we cannot
        // reliably tell hosting from residential, so we do NOT guess here. CLEAN by default.
        return IpRisk.CLEAN;
    }

    /** Refresh the Tor exit set if the TTL has elapsed; best-effort, one loader at a time. */
    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (!torExits.isEmpty() && now - lastLoadAttempt < refreshMillis) return;
        synchronized (loadLock) {
            now = System.currentTimeMillis();
            if (!torExits.isEmpty() && now - lastLoadAttempt < refreshMillis) return;
            lastLoadAttempt = now; // stamp before the call so failures don't hammer the endpoint
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(torListUrl))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) {
                    Set<String> parsed = new HashSet<>();
                    for (String line : res.body().split("\\R")) {
                        String ip = line.trim();
                        if (!ip.isEmpty() && !ip.startsWith("#")) parsed.add(ip);
                    }
                    if (!parsed.isEmpty()) {
                        torExits = Set.copyOf(parsed);
                        log.info("Loaded {} Tor exit nodes for IP reputation", torExits.size());
                    }
                } else {
                    log.warn("Tor exit list fetch returned HTTP {} — IP reputation stays CLEAN-only", res.statusCode());
                }
            } catch (Exception e) {
                log.warn("Tor exit list fetch failed ({}) — IP reputation stays CLEAN-only", e.toString());
            }
        }
    }
}
