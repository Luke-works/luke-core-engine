package com.luke.engine.capability.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecretCrypto}: encrypt/decrypt correctness, IV freshness,
 * tamper-detection (GCM), unknown-key handling, and multi-key rotation. No Spring
 * context — the crypto is built directly and {@code init()} called.
 */
class SecretCryptoTest {

    private static SecretCrypto crypto(String activeKeyId, Map<String, String> keys) {
        SecretsProperties props = new SecretsProperties();
        props.setActiveKeyId(activeKeyId);
        props.setKeys(keys);
        SecretCrypto c = new SecretCrypto(props);
        c.init();
        return c;
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        SecretCrypto crypto = crypto("v1", Map.of("v1", "a-passphrase-master-key"));
        SecretCrypto.Encrypted enc = crypto.encrypt("pm_live_secret_token");

        assertThat(enc.keyId()).isEqualTo("v1");
        assertThat(enc.ciphertext()).isNotBlank();
        assertThat(enc.iv()).isNotBlank();
        assertThat(crypto.decrypt(enc.ciphertext(), enc.iv(), enc.keyId())).isEqualTo("pm_live_secret_token");
    }

    @Test
    void ciphertextDiffersEachTimeButBothDecrypt() {
        SecretCrypto crypto = crypto("v1", Map.of("v1", "a-passphrase-master-key"));
        SecretCrypto.Encrypted a = crypto.encrypt("same-plaintext");
        SecretCrypto.Encrypted b = crypto.encrypt("same-plaintext");

        // Random IV per encryption → different ciphertext for identical input.
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(crypto.decrypt(a.ciphertext(), a.iv(), a.keyId())).isEqualTo("same-plaintext");
        assertThat(crypto.decrypt(b.ciphertext(), b.iv(), b.keyId())).isEqualTo("same-plaintext");
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        SecretCrypto crypto = crypto("v1", Map.of("v1", "a-passphrase-master-key"));
        SecretCrypto.Encrypted enc = crypto.encrypt("do-not-tamper");

        byte[] raw = Base64.getDecoder().decode(enc.ciphertext());
        raw[0] ^= 0x01; // flip a bit
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> crypto.decrypt(tampered, enc.iv(), enc.keyId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownKeyIdIsRejected() {
        SecretCrypto crypto = crypto("v1", Map.of("v1", "a-passphrase-master-key"));
        SecretCrypto.Encrypted enc = crypto.encrypt("value");

        assertThatThrownBy(() -> crypto.decrypt(enc.ciphertext(), enc.iv(), "v999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v999");
    }

    @Test
    void activeKeyMissingFromKeysFailsFast() {
        SecretsProperties props = new SecretsProperties();
        props.setActiveKeyId("v2");
        props.setKeys(Map.of("v1", "only-key"));
        SecretCrypto c = new SecretCrypto(props);

        assertThatThrownBy(c::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotationKeepsOldRowsDecryptable() {
        // A row encrypted under v1 must still decrypt after v2 becomes active.
        SecretCrypto onlyV1 = crypto("v1", Map.of("v1", "key-one-passphrase"));
        SecretCrypto.Encrypted oldRow = onlyV1.encrypt("old-secret");
        assertThat(oldRow.keyId()).isEqualTo("v1");

        SecretCrypto rotated = crypto("v2", Map.of("v1", "key-one-passphrase", "v2", "key-two-passphrase"));
        // New encryptions use v2 …
        assertThat(rotated.encrypt("new-secret").keyId()).isEqualTo("v2");
        // … but the old v1 row still decrypts.
        assertThat(rotated.decrypt(oldRow.ciphertext(), oldRow.iv(), oldRow.keyId())).isEqualTo("old-secret");
    }

    @Test
    void acceptsBase64ThirtyTwoByteKey() {
        String b64Key = Base64.getEncoder().encodeToString(new byte[32]); // 32 zero bytes
        SecretCrypto crypto = crypto("v1", Map.of("v1", b64Key));
        SecretCrypto.Encrypted enc = crypto.encrypt("works-with-raw-key");
        assertThat(crypto.decrypt(enc.ciphertext(), enc.iv(), enc.keyId())).isEqualTo("works-with-raw-key");
    }
}
