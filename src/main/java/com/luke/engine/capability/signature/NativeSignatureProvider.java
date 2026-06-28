package com.luke.engine.capability.signature;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V1 {@link SignatureProvider} (the product layer). Draws the drawn-signature PNG at the field,
 * appends a <b>Certificate of Completion</b> page rendering the IP-stamped audit trail + signer
 * identity + the SHA-256 of the original source, then hands the prepared PDF to a
 * {@link TrustProvider} for the cryptographic PAdES seal. Uses PDFBox only (no iText).
 */
@Component
public class NativeSignatureProvider implements SignatureProvider {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TrustProvider trustProvider;
    private final String reason;
    private final String location;

    public NativeSignatureProvider(TrustProvider trustProvider,
                                   @Value("${luke.sign.reason:Signed via Luke e-signature}") String reason,
                                   @Value("${luke.sign.location:}") String location) {
        this.trustProvider = trustProvider;
        this.reason = reason;
        this.location = location;
    }

    @Override
    public byte[] stampAndSign(StampRequest request) {
        try (PDDocument document = Loader.loadPDF(request.sourcePdf())) {
            drawSignature(document, request);
            appendCertificate(document, request.meta());

            ByteArrayOutputStream prepared = new ByteArrayOutputStream();
            document.save(prepared);

            SealMeta sealMeta = new SealMeta(
                    request.meta().signerName(), request.meta().signerEmail(), reason, location);
            return trustProvider.seal(prepared.toByteArray(), sealMeta);
        } catch (IOException e) {
            throw new IllegalStateException("Signature stamping failed: " + e.getMessage(), e);
        }
    }

    /** Draw the signature PNG at the field rect (single-field path). */
    private void drawSignature(PDDocument document, StampRequest request) throws IOException {
        drawImage(document, request.field(), request.signaturePng());
    }

    /** Draw an image at the field rect, converting UI top-left coords to PDF bottom-left. */
    private void drawImage(PDDocument document, Field field, byte[] png) throws IOException {
        PDPage page = pageFor(document, field);
        PDRectangle box = page.getMediaBox();
        // Decode via ImageIO + LosslessFactory rather than PDImageXObject.createFromByteArray:
        // PDFBox 3.0.x's PNGConverter fast-path throws (AIOOBE) on some valid PNGs (e.g. a
        // signature_pad/canvas export), which createFromByteArray does not catch. ImageIO is robust.
        BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(png));
        if (bufferedImage == null) {
            throw new IllegalArgumentException("signature image is not a readable PNG");
        }
        PDImageXObject image = LosslessFactory.createFromImage(document, bufferedImage);
        float x = box.getLowerLeftX() + (float) field.x();
        float y = box.getUpperRightY() - (float) (field.y() + field.h()); // flip Y origin
        try (PDPageContentStream cs =
                     new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            cs.drawImage(image, x, y, (float) field.w(), (float) field.h());
        }
    }

    /** Draw a line of text within the field rect (DATE/NAME fields), vertically centred. */
    private void drawText(PDDocument document, Field field, String text) throws IOException {
        if (text == null || text.isBlank()) return;
        PDPage page = pageFor(document, field);
        PDRectangle box = page.getMediaBox();
        float size = Math.min(12f, Math.max(7f, (float) field.h() * 0.5f));
        float x = box.getLowerLeftX() + (float) field.x() + 2f;
        float y = box.getUpperRightY() - (float) (field.y() + field.h()) + ((float) field.h() - size) / 2f;
        try (PDPageContentStream cs =
                     new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(text));
            cs.endText();
        }
    }

    /** Resolve + validate the target page (range + rotation), shared by image/text stamping. */
    private PDPage pageFor(PDDocument document, Field field) {
        int pageIndex = field.page();
        if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
            throw new IllegalArgumentException(
                    "field.page " + pageIndex + " out of range (0.." + (document.getNumberOfPages() - 1) + ")");
        }
        PDPage page = document.getPage(pageIndex);
        // We stamp into the un-rotated mediabox; a rotated page would misplace the mark.
        if (page.getRotation() % 360 != 0) {
            throw new IllegalArgumentException(
                    "Rotated pages are not supported (page " + pageIndex + ", /Rotate " + page.getRotation() + ")");
        }
        return page;
    }

    @Override
    public byte[] sealEnvelope(EnvelopeSealRequest request) {
        try (PDDocument document = Loader.loadPDF(request.sourcePdf())) {
            for (EnvelopeSealRequest.FieldStamp s : request.stamps()) {
                if (s.png() != null) drawImage(document, s.field(), s.png());
                else if (s.text() != null) drawText(document, s.field(), s.text());
            }
            appendEnvelopeCertificate(document, request.meta());

            ByteArrayOutputStream prepared = new ByteArrayOutputStream();
            document.save(prepared);

            var signers = request.meta().signers();
            String name = signers != null && !signers.isEmpty() ? signers.get(0).name() : "Signed";
            String email = signers != null && !signers.isEmpty() ? signers.get(0).email() : "";
            return trustProvider.seal(prepared.toByteArray(), new SealMeta(name, email, reason, location));
        } catch (IOException e) {
            throw new IllegalStateException("Envelope sealing failed: " + e.getMessage(), e);
        }
    }

    /** Multi-signer Certificate of Completion: contract name, source hash, each signer + when. */
    private void appendEnvelopeCertificate(PDDocument document, EnvelopeSealRequest.EnvelopeMeta meta) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float margin = 50f;
        float bottom = margin;
        float y = page.getMediaBox().getHeight() - margin;
        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            y = line(cs, bold, 16f, margin, y, "Certificate of Completion");
            y -= 8f;
            y = line(cs, regular, 10f, margin, y, "Document: " + nz(meta.name()));
            y = line(cs, regular, 10f, margin, y, "Source SHA-256: " + nz(meta.sourceSha256()));
            y -= 8f;
            y = line(cs, bold, 11f, margin, y, "Signers");
            if (meta.signers() != null) {
                for (EnvelopeSealRequest.SignerSummary s : meta.signers()) {
                    if (y <= bottom + 24f) break;
                    y = line(cs, regular, 9f, margin, y,
                            nz(s.name()) + " <" + nz(s.email()) + ">   signed " + nz(s.signedAt()));
                }
            }
        }
    }

    /** Append the Certificate of Completion: signer, source hash, and the ordered audit trail. */
    private void appendCertificate(PDDocument document, AuditMeta meta) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);

        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        float margin = 50f;
        float bottom = margin;
        float y = page.getMediaBox().getHeight() - margin;

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            y = line(cs, bold, 16f, margin, y, "Certificate of Completion");
            y -= 8f;
            y = line(cs, regular, 10f, margin, y, "Document signed via Luke e-signature.");
            y = line(cs, regular, 10f, margin, y,
                    "Signer: " + nz(meta.signerName()) + " <" + nz(meta.signerEmail()) + ">");
            y = line(cs, regular, 10f, margin, y, "Source SHA-256: " + nz(meta.sourceSha256()));
            y -= 8f;
            y = line(cs, bold, 11f, margin, y, "Audit trail");

            if (meta.events() != null) {
                for (SignatureAuditEvent e : meta.events()) {
                    if (y <= bottom + 24f) break; // single-page V1: stop before overflow
                    String when = e.getAt() != null ? TS.format(e.getAt()) : "";
                    String risk = e.getIpRisk() != null ? e.getIpRisk().name() : "—";
                    String head = when + "   " + nz(e.getAction())
                            + "   IP " + nz(e.getIpAddress()) + " (" + risk + ")"
                            + "   actor " + nz(e.getActor());
                    y = line(cs, regular, 9f, margin, y, head);
                    if (e.getUserAgent() != null && !e.getUserAgent().isBlank()) {
                        y = line(cs, regular, 8f, margin + 14f, y, "UA: " + truncate(e.getUserAgent(), 110));
                    }
                }
            }
        }
    }

    private float line(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
        return y - (size + 5f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Keep only chars renderable by Helvetica/WinAnsi; flatten whitespace; replace the rest. */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') b.append(' ');
            else if ((c >= 32 && c <= 126) || (c >= 160 && c <= 255)) b.append(c);
            else b.append('?');
        }
        return b.toString();
    }
}
