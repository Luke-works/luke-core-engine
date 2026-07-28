package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.document.DocumentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The submission backstop runs at the CHOKE POINT, so it protects every door — not just the embed
 * webhook it originally shipped for. Every submit path in the system (public embed, OTP recipient
 * portal, authenticated in-app fill) reaches {@link FormSubmissionService#submit}, so proving it
 * here proves it for all three; a fourth door added later inherits it for free.
 */
class FormSubmissionServiceValidationTest {

    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormSubmissionOutboxRepository outbox = mock(FormSubmissionOutboxRepository.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final FormEventPublisher events = mock(FormEventPublisher.class);
    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);

    private final FormSubmissionService service =
            new FormSubmissionService(instances, outbox, documents, events, forms, versions);

    /** fullName is required; age and notes are optional. */
    private static final String SCHEMA = """
            {"entities":{
              "e1":{"type":"text","attributes":{"key":"fullName","required":true}},
              "e2":{"type":"number","attributes":{"key":"age"}},
              "e3":{"type":"text","attributes":{"key":"notes"}}
            }}""";

    @BeforeEach
    void wireSchemaLookup() {
        FormDefinition def = new FormDefinition();
        def.setId("f1");
        FormVersion version = new FormVersion();
        version.setSchema(SCHEMA);
        when(forms.findByTenantIdAndCode("t1", "FM-1")).thenReturn(Optional.of(def));
        when(versions.findByFormIdAndVersion("f1", 1)).thenReturn(Optional.of(version));
        when(outbox.findByBusinessKey(anyString())).thenReturn(Optional.empty());
        when(documents.attachmentAudit(anyString(), anyString(), any())).thenReturn(List.of());
    }

    private FormInstance instance(Map<String, Object> storedData) {
        FormInstance i = new FormInstance();
        i.setId("i1");
        i.setTenantId("t1");
        i.setDefinitionCode("FM-1");
        i.setVersion(1);
        i.setState(FormInstanceStates.IN_PROGRESS);
        i.setData(storedData);
        return i;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void stripsUndeclaredKeysFromANonEmbedSubmission() {
        // The authenticated + OTP-portal doors previously wrote whatever the client sent, straight
        // into the instance data that feeds process variables. They no longer do.
        FormInstance inst = instance(null);
        service.submit(inst, map("fullName", "Ada", "isAdmin", true, "__proto__", "x"));
        assertThat(inst.getData()).containsOnlyKeys("fullName");
    }

    @Test
    void enforcesRequiredOnANonEmbedSubmission() {
        FormInstance inst = instance(null);
        assertThatThrownBy(() -> service.submit(inst, map("age", 30)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fullName");
        // rejected BEFORE any state change — a 400 must not leave a half-submitted instance
        assertThat(inst.getState()).isEqualTo(FormInstanceStates.IN_PROGRESS);
    }

    @Test
    void validatesTheMergedMapSoADeltaSubmitStillPasses() {
        // The real shape of an outbound/portal submit: the required field was captured in an earlier
        // save and only the remaining answers are posted now. Validating the delta alone would 400.
        FormInstance inst = instance(map("fullName", "Ada"));
        service.submit(inst, map("age", 30));
        assertThat(inst.getData()).containsEntry("fullName", "Ada").containsEntry("age", 30);
        assertThat(inst.getState()).isEqualTo(FormInstanceStates.SUBMITTED);
    }

    @Test
    void cleansStoredDataEvenWhenNothingNewIsPosted() {
        // The embed door sets data on the instance and passes a null delta; the choke point must
        // still validate what's there rather than trusting an upstream call to have done it.
        FormInstance inst = instance(map("fullName", "Ada", "smuggled", "value"));
        service.submit(inst, null);
        assertThat(inst.getData()).containsOnlyKeys("fullName");
    }

    @Test
    void stripsControlCharactersOnEverySubmitPath() {
        FormInstance inst = instance(null);
        service.submit(inst, map("fullName", "Ada" + (char) 0 + "Lovelace"));
        assertThat(inst.getData()).containsEntry("fullName", "AdaLovelace");
    }

    @Test
    void keepsEverythingWhenTheSchemaCannotBeResolved() {
        // An instance whose definition/version row is gone must not have its submission emptied —
        // the field contract is unknown, so fall back to cleaning without stripping.
        when(forms.findByTenantIdAndCode("t1", "FM-1")).thenReturn(Optional.empty());
        FormInstance inst = instance(null);
        service.submit(inst, map("anything", "goes"));
        assertThat(inst.getData()).containsEntry("anything", "goes");
        assertThat(inst.getState()).isEqualTo(FormInstanceStates.SUBMITTED);
    }
}
