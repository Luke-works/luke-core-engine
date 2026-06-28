package com.luke.engine.capability.signature;

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
 * Public, token-authenticated per-recipient signing for a {@link SignatureInstance}
 * ({@code /api/public/sign-instance/{token}}). The unguessable recipient token is the sole auth —
 * NO tenant header. GET returns the recipient's signing session (document + their fields + the
 * merged data values); POST records the drawn signature and advances the contract toward closure.
 */
@RestController
@RequestMapping("/api/public/sign-instance")
public class PublicInstanceSignController {

    /** Bound the only un-gated write (a drawn PNG) — DoS guard on the public route. */
    private static final int MAX_PNG_BYTES = 512 * 1024;

    private final SignatureInstanceService service;

    public PublicInstanceSignController(SignatureInstanceService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public SignatureInstanceService.RecipientSession view(@PathVariable String token) {
        return service.viewForSigning(token);
    }

    @PostMapping("/{token}")
    public Map<String, Object> sign(@PathVariable String token, @RequestBody SignBody body) {
        if (body == null || body.signaturePngBase64() == null || body.signaturePngBase64().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "signaturePngBase64 is required");
        }
        byte[] png = decodePng(body.signaturePngBase64());
        service.sign(token, png, Boolean.TRUE.equals(body.consent()));
        return Map.of("ok", true);
    }

    /** Decode a (possibly data-URL-prefixed) base64 PNG, bounded + magic-byte checked. */
    private static byte[] decodePng(String base64) {
        String raw = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "signature is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_PNG_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "signature image is too large");
        }
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.length < 8 || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "signature must be a PNG");
        }
        return bytes;
    }

    public record SignBody(String signaturePngBase64, Boolean consent) {}
}
