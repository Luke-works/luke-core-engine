package com.luke.engine.capability.form;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Calls luke-file-proxy's internal headless-Chromium render endpoint (core → file-proxy) to turn a
 * completed submission into a PDF and store it at a core-chosen storage key. The proxy returns only the
 * byte size + SHA-256; core owns the Document registry handshake in-process. Authenticated with the
 * shared internal key. Inactive (no-op) when {@code luke.render.base-url} is unset, so a deploy without
 * the renderer configured simply skips submission PDFs instead of failing submissions.
 */
@Component
public class FormPdfRenderClient {

    private final RestClient http;

    public FormPdfRenderClient(@Value("${luke.render.base-url:}") String baseUrl,
                               @Value("${luke.internal.shared-secret:}") String internalSecret) {
        this.http = StringUtils.hasText(baseUrl)
                ? RestClient.builder().baseUrl(baseUrl).defaultHeader("X-Internal-Key", internalSecret).build()
                : null;
    }

    /** Whether the renderer is configured (a base URL is set). */
    public boolean isConfigured() {
        return http != null;
    }

    /** Render {@code data} against {@code schema} (raw schema JSON string) and store the PDF at
     *  {@code storageKey}; returns the stored size + checksum for the Document finalize/register. */
    public Result render(String tenantId, String storageKey, String schema, Object data, Object theme) {
        return render(tenantId, storageKey, schema, data, theme, null);
    }

    /** As above, with the submission-record (provenance) block the harness prints under the form. */
    public Result render(String tenantId, String storageKey, String schema, Object data, Object theme,
                         Object provenance) {
        return http.post().uri("/internal/render/form-pdf")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(tenantId, storageKey, schema, data, theme, provenance))
                .retrieve()
                .body(Result.class);
    }

    public record Request(String tenantId, String storageKey, String schema, Object data, Object theme,
                          Object provenance) {}

    public record Result(Long sizeBytes, String sha256) {}
}
