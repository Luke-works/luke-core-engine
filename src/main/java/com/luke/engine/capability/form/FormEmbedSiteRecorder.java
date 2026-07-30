package com.luke.engine.capability.form;

import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records which websites are embedding a form, from the {@code Referer} of the iframe's request for the
 * embed page. Feeds the authoring UI's "Embedded on" list — see {@link FormEmbedSite} for what this data
 * is and (importantly) is not.
 *
 * <p>Three properties matter because the caller is a PUBLIC, unauthenticated page:
 * <ul>
 *   <li><b>Never breaks the page.</b> Every failure is swallowed — an embedded form must render even if
 *       this bookkeeping cannot. It also runs in its OWN transaction ({@code REQUIRES_NEW}) so a failed
 *       insert can't mark the caller's transaction rollback-only.
 *   <li><b>Bounded.</b> At most {@code maxSitesPerForm} origins are stored per form. The header is
 *       client-supplied, so without a cap a spoofing loop could write unbounded rows.
 *   <li><b>Cheap.</b> An origin already recorded is only re-written once its {@code lastSeenAt} is older
 *       than the throttle window, so a busy embed costs one write per window, not one per page view.
 * </ul>
 */
@Service
public class FormEmbedSiteRecorder {

    private static final Logger log = LoggerFactory.getLogger(FormEmbedSiteRecorder.class);

    /** An origin longer than this is not a real one (the column is 255). */
    private static final int ORIGIN_MAX = 255;

    private final FormEmbedSiteRepository sites;
    private final int maxSitesPerForm;
    private final Duration throttle;

    public FormEmbedSiteRecorder(FormEmbedSiteRepository sites,
                                 @Value("${luke.embed.sites.max-per-form:50}") int maxSitesPerForm,
                                 @Value("${luke.embed.sites.throttle-minutes:5}") long throttleMinutes) {
        this.sites = sites;
        this.maxSitesPerForm = maxSitesPerForm;
        this.throttle = Duration.ofMinutes(Math.max(0, throttleMinutes));
    }

    /**
     * Note that {@code referer} framed this form. Accepts the raw header and keeps only its origin;
     * ignores anything that isn't a usable http(s) origin.
     *
     * <p>{@code secFetchDest} distinguishes a real embed from a top-level visit. The authoring UI's "Open
     * preview" button opens {@code /embed/{token}} in a NEW TAB, whose {@code Referer} is our own app
     * origin — without this check, previewing your own form would list Lukeflow as a site embedding it.
     * A browser sending {@code iframe} is framing us; anything else ({@code document}, {@code empty}) is
     * not. When the header is absent (older browsers, non-browser clients) we still record, so the
     * feature degrades to best-effort rather than going blank.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String tenantId, String formCode, String referer, String secFetchDest) {
        try {
            if (secFetchDest != null && !secFetchDest.isBlank()
                    && !"iframe".equalsIgnoreCase(secFetchDest.trim())) {
                return; // a top-level visit (e.g. our own "Open preview"), not an embed
            }
            String origin = originOf(referer);
            if (origin == null || tenantId == null || formCode == null) return;

            FormEmbedSite existing = sites.findByTenantIdAndFormCodeAndOrigin(tenantId, formCode, origin).orElse(null);
            if (existing != null) {
                LocalDateTime cutoff = LocalDateTime.now().minus(throttle);
                if (existing.getLastSeenAt() != null && existing.getLastSeenAt().isAfter(cutoff)) return; // throttled
                existing.setLastSeenAt(LocalDateTime.now());
                existing.setRenderCount(existing.getRenderCount() + 1);
                sites.save(existing);
                return;
            }
            if (sites.countByTenantIdAndFormCode(tenantId, formCode) >= maxSitesPerForm) return; // capped
            sites.save(new FormEmbedSite(tenantId, formCode, origin));
        } catch (RuntimeException e) {
            // Bookkeeping only — a duplicate-key race (two first hits at once) or any store failure must
            // never affect serving the form.
            log.debug("embed-site record skipped for {}/{}: {}", tenantId, formCode, e.toString());
        }
    }

    /** The scheme://host[:port] of a referer URL, or null when it isn't one we should store. */
    static String originOf(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(referer.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            String origin = scheme.toLowerCase() + "://" + host.toLowerCase()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            return origin.length() > ORIGIN_MAX ? null : origin;
        } catch (IllegalArgumentException e) {
            return null; // malformed header
        }
    }
}
