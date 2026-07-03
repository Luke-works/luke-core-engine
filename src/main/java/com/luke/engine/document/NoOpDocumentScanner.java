package com.luke.engine.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link DocumentScanner} (DOC-6): allows everything. The real scanner is OUT of V1 — when one
 * ships (an async S3-reading worker), provide a {@code DocumentScanner} bean and this no-op steps aside
 * ({@link ConditionalOnMissingBean}).
 */
@Configuration
public class NoOpDocumentScanner {

    @Bean
    @ConditionalOnMissingBean(DocumentScanner.class)
    public DocumentScanner allowAllDocumentScanner() {
        return doc -> DocumentScanner.ScanVerdict.ok();
    }
}
