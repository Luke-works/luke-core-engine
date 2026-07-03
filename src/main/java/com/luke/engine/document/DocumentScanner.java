package com.luke.engine.document;

/**
 * Integrity / AV-scan SEAM (DOC-6). Invoked synchronously at finalize, after the proxy has streamed the
 * bytes to S3 and core has recorded the server-computed SHA-256. A verdict of {@link ScanVerdict#infected}
 * flips the document to QUARANTINED, which makes {@code GET /content} return 423.
 *
 * <p>The real scanner is OUT of V1 ({@link NoOpDocumentScanner} allows everything). Because bytes never
 * reach core, this seam is given the {@link Document} (storageKey + sha256 + size + contentType) — NOT
 * the bytes. <b>Upgrade path:</b> swap in an async worker that pulls the object from S3 (via the proxy /
 * a direct read), runs ClamAV/etc., and PATCHes status→QUARANTINED out-of-band; the {@code QUARANTINED}
 * status + the 423 gate already exist, so no schema or API change is needed to go live.
 */
public interface DocumentScanner {

    ScanVerdict scan(Document doc);

    /** Scan outcome. {@code clean=false} quarantines the document. */
    record ScanVerdict(boolean clean, String reason) {
        public static ScanVerdict ok() {
            return new ScanVerdict(true, null);
        }

        public static ScanVerdict infected(String reason) {
            return new ScanVerdict(false, reason);
        }
    }
}
