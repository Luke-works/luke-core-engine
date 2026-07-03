package com.luke.engine.capability.email;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public, read-only metadata so the client and server share one source of truth
 * instead of hand-maintaining parallel copies (#38).
 *
 * <p>Served under {@code /api/public/**} — the unauthenticated surface (same family
 * as the form embed); the list is non-sensitive (well-known free mailbox providers)
 * and identical for every caller, so it carries a long cache header.
 */
@RestController
@RequestMapping("/api/public/meta")
public class PublicEmailMetaController {

    /** The free/personal mailbox providers, sorted, from {@link OrgDomainMatcher}. */
    @GetMapping("/free-email-domains")
    public ResponseEntity<Map<String, List<String>>> freeEmailDomains() {
        List<String> providers = List.copyOf(new TreeSet<>(OrgDomainMatcher.freeProviders()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .body(Map.of("providers", providers));
    }
}
