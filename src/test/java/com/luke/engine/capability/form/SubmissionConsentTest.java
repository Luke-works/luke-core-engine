package com.luke.engine.capability.form;

import com.luke.engine.usage.UsageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luke.engine.document.DocumentService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * The CONSENT RECORD — what makes a submission provable rather than merely logged.
 *
 * <p>Two properties matter more than the rest and are each pinned by a test here:
 *
 * <ol>
 *   <li><b>The gate cannot be bypassed.</b> It runs in {@link FormSubmissionService#submit}, the single
 *       choke point every door funnels through, and refuses rather than recording a submission with no
 *       evidence — including when the calling door reports no consent state at all.</li>
 *   <li><b>The recorded wording comes from the server.</b> It is read from the schema of the version the
 *       instance is pinned to, so a client that alters what it displays still cannot alter what we
 *       store.</li>
 * </ol>
 */
class SubmissionConsentTest {

    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormSubmissionOutboxRepository outbox = mock(FormSubmissionOutboxRepository.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final FormEventPublisher events = mock(FormEventPublisher.class);
    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);

    private final FormSubmissionService service =
            new FormSubmissionService(instances, outbox, documents, events, forms, versions, mock(UsageService.class));

    private static final String TERMS = "I agree to the Acme terms of service and privacy notice.";

    /** A schema whose settings switch consent on with explicit wording. */
    private static String schemaWithConsent(String text) {
        return """
                {"entities":{"e1":{"type":"text","attributes":{"key":"fullName"}}},
                 "settings":{"consent":{"enabled":true,"text":"%s"}}}""".formatted(text);
    }

    private static final String SCHEMA_NO_CONSENT =
            """
            {"entities":{"e1":{"type":"text","attributes":{"key":"fullName"}}}}""";

    private void serve(String schemaJson) {
        FormDefinition def = new FormDefinition();
        def.setId("f1");
        FormVersion v = new FormVersion();
        v.setSchema(schemaJson);
        when(forms.findByTenantIdAndCode("t1", "FM-1")).thenReturn(Optional.of(def));
        when(versions.findByFormIdAndVersion("f1", 3)).thenReturn(Optional.of(v));
    }

    @BeforeEach
    void wire() {
        when(outbox.findByBusinessKey(anyString())).thenReturn(Optional.empty());
        when(documents.attachmentAudit(anyString(), anyString(), any())).thenReturn(List.of());
    }

    private FormInstance instance() {
        FormInstance i = new FormInstance();
        i.setId("i1");
        i.setTenantId("t1");
        i.setDefinitionCode("FM-1");
        i.setVersion(3);
        i.setState(FormInstanceStates.IN_PROGRESS);
        return i;
    }

    private static SubmissionSource agreed(boolean consentAgreed) {
        return new SubmissionSource("203.0.113.4", "UA/1", SubmissionSource.VIA_EMBED, consentAgreed);
    }

    private static Map<String, Object> data() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", "Ada");
        return m;
    }

    /* ── the gate ─────────────────────────────────────────────────────────── */

    @Test
    void refusesASubmissionWhenTheFormRequiresConsentAndTheFillerDidNotAgree() {
        serve(schemaWithConsent(TERMS));
        FormInstance inst = instance();

        assertThatThrownBy(() -> service.submit(inst, data(), null, agreed(false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("accept the agreement");

        // Refused BEFORE anything is written: no state change, no instance row, no queued process.
        assertThat(inst.getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
        assertThat(inst.getConsentText()).isNull();
        verify(instances, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void refusesWhenTheCallingDoorReportsNoConsentStateAtAll() {
        // A submit path that passes no SubmissionSource (the legacy 2-arg overload, or a door added
        // later that forgets) must FAIL rather than quietly recording an unevidenced submission.
        serve(schemaWithConsent(TERMS));
        FormInstance inst = instance();

        assertThatThrownBy(() -> service.submit(inst, data()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("accept the agreement");
        assertThat(inst.getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    @Test
    void recordsTheAgreementWhenTheFillerAgreed() {
        serve(schemaWithConsent(TERMS));
        FormInstance inst = instance();

        service.submit(inst, data(), null, agreed(true));

        assertThat(inst.getState()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(inst.getConsentText()).isEqualTo(TERMS);
        assertThat(inst.getConsentAgreedAt()).isNotNull();
    }

    @Test
    void leavesNoConsentRecordOnAFormThatDoesNotAskForOne() {
        // Absent must mean "not required", never "captured as empty" — anything reading these rows
        // has to be able to tell the two apart.
        serve(SCHEMA_NO_CONSENT);
        FormInstance inst = instance();

        service.submit(inst, data(), null, agreed(false));

        assertThat(inst.getState()).isEqualTo(FormInstanceStates.SUBMITTED);
        assertThat(inst.getConsentText()).isNull();
        assertThat(inst.getConsentAgreedAt()).isNull();
    }

    /* ── the recorded wording is the server's, not the client's ───────────── */

    @Test
    void storesTheServedVersionsWordingNotAnythingFromTheRequest() {
        // The request carries only the tick. Whatever a tampered client shows its user, the statement we
        // keep is the one the pinned version actually published.
        serve(schemaWithConsent("Version 3 wording, as published."));
        FormInstance inst = instance();

        service.submit(inst, data(), null, agreed(true));

        assertThat(inst.getConsentText()).isEqualTo("Version 3 wording, as published.");
    }

    @Test
    void isWriteOnceSoARetryKeepsTheOriginalAgreement() {
        serve(schemaWithConsent(TERMS));
        FormInstance inst = instance();
        LocalDateTime original = LocalDateTime.now().minusDays(2);
        inst.setConsentText("The wording they actually saw two days ago.");
        inst.setConsentAgreedAt(original);

        service.submit(inst, data(), null, agreed(true));

        assertThat(inst.getConsentText()).isEqualTo("The wording they actually saw two days ago.");
        assertThat(inst.getConsentAgreedAt()).isEqualTo(original);
    }

    @Test
    void travelsWithTheSubmissionIntoTheProcessMetadata() {
        // The instance row is subject to retention; formMetaData is the copy that outlives it.
        serve(schemaWithConsent(TERMS));
        service.submit(instance(), data(), null, agreed(true));

        ArgumentCaptor<FormSubmissionOutbox> row = ArgumentCaptor.forClass(FormSubmissionOutbox.class);
        verify(outbox).save(row.capture());
        assertThat(row.getValue().getFormMetaJson())
                .contains("\"consent\"")
                .contains(TERMS)
                .contains("\"agreedAt\"");
    }

    @Test
    void omitsTheConsentKeyFromMetadataWhenNoneWasRequired() {
        serve(SCHEMA_NO_CONSENT);
        service.submit(instance(), data(), null, agreed(false));

        ArgumentCaptor<FormSubmissionOutbox> row = ArgumentCaptor.forClass(FormSubmissionOutbox.class);
        verify(outbox).save(row.capture());
        assertThat(row.getValue().getFormMetaJson()).doesNotContain("\"consent\"");
    }

    /* ── ConsentTerms: reading the requirement off a schema ───────────────── */

    @Test
    void consentIsOffUnlessTheFormSwitchesItOn() {
        assertThat(ConsentTerms.requiredText(SCHEMA_NO_CONSENT)).isNull();
        assertThat(ConsentTerms.requiredText(
                """
                {"entities":{},"settings":{"consent":{"enabled":false,"text":"ignored"}}}"""))
                .isNull();
        assertThat(ConsentTerms.isRequired(SCHEMA_NO_CONSENT)).isFalse();
    }

    @Test
    void aFormWithConsentOnButNoWordingStillRequiresAgreement() {
        // The one failure mode that would silently cost a tenant their evidence. The builder blocks
        // saving this, but a hand-edited schema must not be able to waive the requirement.
        String blank = """
                {"entities":{},"settings":{"consent":{"enabled":true,"text":"   "}}}""";
        assertThat(ConsentTerms.requiredText(blank)).isEqualTo(ConsentTerms.DEFAULT_TEXT);
        assertThat(ConsentTerms.isRequired(blank)).isTrue();
    }

    @Test
    void anUnreadableSchemaImpliesNoRequirement() {
        // We cannot assert a form asked for consent if we cannot read what it asked.
        assertThat(ConsentTerms.requiredText("not json at all")).isNull();
        assertThat(ConsentTerms.requiredText(null)).isNull();
        assertThat(ConsentTerms.requiredText("  ")).isNull();
    }

    @Test
    void anOverlongStatementIsTruncatedToFitStorage() {
        String huge = "A".repeat(ConsentTerms.MAX_LENGTH + 500);
        assertThat(ConsentTerms.requiredText(schemaWithConsent(huge))).hasSize(ConsentTerms.MAX_LENGTH);
    }
}
