package com.luke.engine.workflow.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests for {@link NangoWebhookVerifier}: the sha256 signature check + default-lenient policy. */
class NangoWebhookVerifierTest {

    private NangoWebhookVerifier verifier(String secret, boolean require) {
        NangoWebhookVerifier v = new NangoWebhookVerifier();
        ReflectionTestUtils.setField(v, "secretKey", secret);
        ReflectionTestUtils.setField(v, "requireSignature", require);
        return v;
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        String body = "{\"type\":\"auth\"}";
        String sig = sha256Hex("sekret" + body);
        assertThat(verifier("sekret", true).verify(sig, body)).isTrue();
    }

    @Test
    void rejectsAWrongSignature() {
        assertThat(verifier("sekret", true).verify("deadbeef", "{\"type\":\"auth\"}")).isFalse();
    }

    @Test
    void lenientWhenNoSecretConfigured() {
        assertThat(verifier("", false).verify(null, "{}")).isTrue();
    }

    @Test
    void failClosedWhenSignatureRequiredButNoSecret() {
        assertThat(verifier("", true).verify(null, "{}")).isFalse();
    }
}
