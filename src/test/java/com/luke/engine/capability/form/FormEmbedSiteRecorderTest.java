package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The observed "Embedded on" registry. Written from a client-supplied Referer on a PUBLIC page, so the
 * three properties under test are: only real origins get stored, the list is capped, and repeat views are
 * throttled — and nothing here may ever break serving the form.
 */
class FormEmbedSiteRecorderTest {

    private final FormEmbedSiteRepository sites = mock(FormEmbedSiteRepository.class);
    private final FormEmbedSiteRecorder recorder = new FormEmbedSiteRecorder(sites, 3, 5);

    /* ── origin extraction ────────────────────────────────────────── */

    @Test
    void keepsOnlyTheOriginOfAReferer() {
        // Browsers usually send origin-only for a cross-origin iframe, but a full URL must also reduce to
        // the origin — we never want to store a customer's page paths or query strings.
        assertThat(FormEmbedSiteRecorder.originOf("https://acme.com/careers/apply?ref=x#top"))
                .isEqualTo("https://acme.com");
        assertThat(FormEmbedSiteRecorder.originOf("https://Shop.ACME.com/")).isEqualTo("https://shop.acme.com");
        assertThat(FormEmbedSiteRecorder.originOf("http://localhost:5173/page")).isEqualTo("http://localhost:5173");
    }

    @Test
    void rejectsAnythingThatIsNotAnHttpOrigin() {
        assertThat(FormEmbedSiteRecorder.originOf(null)).isNull();
        assertThat(FormEmbedSiteRecorder.originOf("   ")).isNull();
        assertThat(FormEmbedSiteRecorder.originOf("not a url")).isNull();
        assertThat(FormEmbedSiteRecorder.originOf("javascript:alert(1)")).isNull();
        assertThat(FormEmbedSiteRecorder.originOf("file:///etc/passwd")).isNull();
        assertThat(FormEmbedSiteRecorder.originOf("data:text/html,<h1>x")).isNull();
        assertThat(FormEmbedSiteRecorder.originOf("https://" + "x".repeat(300) + ".com")).isNull(); // over column
    }

    /* ── recording ────────────────────────────────────────────────── */

    @Test
    void firstSightingOfAnOriginIsStored() {
        when(sites.findByTenantIdAndFormCodeAndOrigin("t1", "FM-1", "https://acme.com"))
                .thenReturn(Optional.empty());
        when(sites.countByTenantIdAndFormCode("t1", "FM-1")).thenReturn(0L);

        recorder.record("t1", "FM-1", "https://acme.com/apply", "iframe");

        org.mockito.ArgumentCaptor<FormEmbedSite> saved = org.mockito.ArgumentCaptor.forClass(FormEmbedSite.class);
        verify(sites).save(saved.capture());
        assertThat(saved.getValue().getOrigin()).isEqualTo("https://acme.com");
        assertThat(saved.getValue().getFormCode()).isEqualTo("FM-1");
        assertThat(saved.getValue().getRenderCount()).isEqualTo(1);
    }

    @Test
    void aRepeatViewWithinTheThrottleWindowWritesNothing() {
        FormEmbedSite existing = new FormEmbedSite("t1", "FM-1", "https://acme.com");
        existing.setLastSeenAt(LocalDateTime.now().minusMinutes(1)); // window is 5 min
        when(sites.findByTenantIdAndFormCodeAndOrigin("t1", "FM-1", "https://acme.com"))
                .thenReturn(Optional.of(existing));

        recorder.record("t1", "FM-1", "https://acme.com", "iframe");

        verify(sites, never()).save(any());
    }

    @Test
    void aViewAfterTheThrottleWindowBumpsLastSeenAndTheSampledCount() {
        FormEmbedSite existing = new FormEmbedSite("t1", "FM-1", "https://acme.com");
        existing.setLastSeenAt(LocalDateTime.now().minusHours(2));
        existing.setRenderCount(7);
        when(sites.findByTenantIdAndFormCodeAndOrigin("t1", "FM-1", "https://acme.com"))
                .thenReturn(Optional.of(existing));

        recorder.record("t1", "FM-1", "https://acme.com", "iframe");

        verify(sites).save(existing);
        assertThat(existing.getRenderCount()).isEqualTo(8);
        assertThat(existing.getLastSeenAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void theListIsCappedSoAForgedRefererCannotWriteUnboundedRows() {
        when(sites.findByTenantIdAndFormCodeAndOrigin(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sites.countByTenantIdAndFormCode("t1", "FM-1")).thenReturn(3L); // cap is 3

        recorder.record("t1", "FM-1", "https://spoofed-999.example", "iframe");

        verify(sites, never()).save(any());
    }

    @Test
    void aStoreFailureIsSwallowedSoTheEmbedStillRenders() {
        when(sites.findByTenantIdAndFormCodeAndOrigin(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        // No exception escapes — the form page must render even when this bookkeeping cannot.
        recorder.record("t1", "FM-1", "https://acme.com", "iframe");
    }

    @Test
    void aTopLevelVisitIsNotAnEmbed() {
        // The authoring UI's "Open preview" opens /embed/{token} in a new TAB, so its Referer is our own
        // app origin. Recording that would tell an author Lukeflow is embedding their form.
        recorder.record("t1", "FM-1", "https://app.lukeflow.com/forms/f1", "document");
        recorder.record("t1", "FM-1", "https://app.lukeflow.com/forms/f1", "empty");
        verify(sites, never()).save(any());
    }

    @Test
    void recordsWhenTheBrowserSendsNoFetchMetadata() {
        // Older browsers omit Sec-Fetch-Dest — degrade to best-effort rather than collecting nothing.
        when(sites.findByTenantIdAndFormCodeAndOrigin(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sites.countByTenantIdAndFormCode("t1", "FM-1")).thenReturn(0L);
        recorder.record("t1", "FM-1", "https://acme.com", null);
        verify(sites).save(any(FormEmbedSite.class));
    }

    @Test
    void missingIdentifiersAreIgnored() {
        recorder.record(null, "FM-1", "https://acme.com", "iframe");
        recorder.record("t1", null, "https://acme.com", "iframe");
        recorder.record("t1", "FM-1", null, "iframe");
        verify(sites, never()).save(any());
    }
}
