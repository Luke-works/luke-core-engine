package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.document.Document;
import com.luke.engine.document.DocumentRegistration;
import com.luke.engine.document.DocumentService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for the "Save submission as Attachment" PDF generation (opt-in, best-effort). */
class FormSubmissionPdfServiceTest {

    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final FormPdfRenderClient render = mock(FormPdfRenderClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private FormSubmissionPdfService service(boolean enabled, boolean configured) {
        when(render.isConfigured()).thenReturn(configured);
        return new FormSubmissionPdfService(instances, forms, versions, documents, render, mapper, enabled);
    }

    private void stubInstance(String schemaJson) {
        FormInstance inst = new FormInstance();
        inst.setId("inst-1");
        inst.setTenantId("t1");
        inst.setDefinitionCode("FORM-CODE");
        inst.setVersion(3);
        inst.setData(Map.of("name", "Ada"));
        when(instances.findById("inst-1")).thenReturn(Optional.of(inst));
        FormDefinition def = new FormDefinition();
        def.setId("def-1");
        def.setTenantId("t1");
        def.setCode("FORM-CODE");
        when(forms.findByTenantIdAndCode("t1", "FORM-CODE")).thenReturn(Optional.of(def));
        FormVersion ver = new FormVersion();
        ver.setFormId("def-1");
        ver.setVersion(3);
        ver.setSchema(schemaJson);
        when(versions.findByFormIdAndVersion("def-1", 3)).thenReturn(Optional.of(ver));
    }

    @Test
    void rendersAndRegisters_whenOptedIn() {
        stubInstance("{\"root\":[],\"entities\":{},\"settings\":{\"saveSubmissionAsPdf\":true}}");
        when(render.render(eq("t1"), anyString(), anyString(), any(), any()))
                .thenReturn(new FormPdfRenderClient.Result(1234L, "sha-abc"));

        service(true, true).maybeGenerate("t1", "inst-1", "pid-9", "SM-KEY");

        // Renders into a deterministic, tenant-relative key under the process business key folder.
        verify(render).render(eq("t1"), eq("SM-KEY/inst-1-submission.pdf"), anyString(), any(), isNull());
        ArgumentCaptor<DocumentRegistration> cap = ArgumentCaptor.forClass(DocumentRegistration.class);
        verify(documents).registerStored(cap.capture());
        DocumentRegistration r = cap.getValue();
        assertEquals("FORMS", r.capability());
        assertEquals(Document.KIND_FORM_SUBMISSION_PDF, r.kind());
        assertEquals("inst-1", r.ownerEntityId());
        assertEquals("pid-9", r.processInstanceId());
        assertEquals("application/pdf", r.contentType());
        assertEquals(1234L, r.sizeBytes());
        assertEquals("sha-abc", r.sha256());
    }

    @Test
    void skips_whenNotOptedIn() {
        stubInstance("{\"settings\":{\"saveSubmissionAsPdf\":false}}");
        service(true, true).maybeGenerate("t1", "inst-1", "pid-9", "SM-KEY");
        verify(render, never()).render(any(), any(), any(), any(), any());
        verifyNoInteractions(documents);
    }

    @Test
    void skips_whenRendererNotConfigured() {
        service(true, false).maybeGenerate("t1", "inst-1", "pid-9", "SM-KEY");
        verifyNoInteractions(instances, forms, versions, documents);
    }

    @Test
    void swallowsErrors_neverThrows() {
        stubInstance("{\"settings\":{\"saveSubmissionAsPdf\":true}}");
        when(render.render(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        service(true, true).maybeGenerate("t1", "inst-1", "pid-9", "SM-KEY"); // must not throw
        verify(documents, never()).registerStored(any());
    }
}
