package com.luke.engine.capability.signature;

import com.luke.engine.capability.signature.SignatureService.SigningSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public signing API ({@code /api/public/sign/**}) — authenticated SOLELY by the unguessable
 * sign token, NOT by tenant headers. This path is deliberately ungated (no auth/capability
 * gate): in standalone the {@code DevHeaderAuthFilter} skips {@code /api/public/**}; at SIG-M it
 * must be added to NEITHER {@code AccessWebConfig} nor {@code GatewayAuthFilter}.
 */
@RestController
@RequestMapping("/api/public/sign")
public class PublicSignatureController {

    private final SignatureService service;

    public PublicSignatureController(SignatureService service) {
        this.service = service;
    }

    /** GET /api/public/sign/{token} — the signing session (marks VIEWED). */
    @GetMapping("/{token}")
    public SigningSessionView view(@PathVariable String token, HttpServletRequest http) {
        SigningSession session = service.viewForSigning(token, http);
        SignatureRequest req = session.request();
        String pdfBase64 = Base64.getEncoder().encodeToString(session.sourcePdf());
        VerificationInfo verification = new VerificationInfo(
                session.verificationRequired(),
                req.getVerificationMethod() == null ? VerificationMethod.NONE.name() : req.getVerificationMethod().name(),
                null); // sentTo is populated by SIG-9 (email OTP)
        return new SigningSessionView(req.getName(), req.getSignerName(), Field.of(req), pdfBase64, verification);
    }

    /** POST /api/public/sign/{token} — apply the signature. */
    @PostMapping("/{token}")
    public Map<String, Object> sign(@PathVariable String token,
                                    @RequestBody(required = false) SignPayload body,
                                    HttpServletRequest http) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        boolean consent = Boolean.TRUE.equals(body.consent());
        byte[] png = decodePng(body.signaturePngBase64());
        service.sign(token, png, consent, body.signerNameTyped(), http);
        return Map.of("ok", true);
    }

    /** A drawn signature PNG is ~10–100KB; 512KB is generous. Bounds the only un-gated route. */
    private static final int MAX_PNG_BYTES = 512 * 1024;

    private static byte[] decodePng(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "signaturePngBase64 is required");
        }
        // Tolerate a data: URL prefix (data:image/png;base64,....).
        String payload = base64;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        payload = payload.strip();
        // Reject oversize BEFORE allocating the decoded array (4 base64 chars → 3 bytes).
        if ((long) payload.length() > ((long) MAX_PNG_BYTES / 3 + 1) * 4) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "signature image is too large");
        }
        byte[] png;
        try {
            png = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "signaturePngBase64 is not valid base64");
        }
        if (png.length > MAX_PNG_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "signature image is too large");
        }
        if (!isPng(png)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "signature must be a PNG image");
        }
        return png;
    }

    /** PNG 8-byte magic signature (89 50 4E 47 0D 0A 1A 0A). */
    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────────────

    /** Signer-facing session: source PDF (base64) + field + verification requirement. */
    public record SigningSessionView(String name, String signerName, Field field,
                                     String pdfBase64, VerificationInfo verification) {}

    public record VerificationInfo(boolean required, String method, String sentTo) {}

    /** Sign body: {signaturePngBase64, consent, signerNameTyped?}. */
    public record SignPayload(String signaturePngBase64, Boolean consent, String signerNameTyped) {}
}
