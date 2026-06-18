package com.luke.engine.capability.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the SecretCrypto key-derivation fix (GHSA-ch6r): explicit base64:/passphrase:
 * prefixes work, AND unprefixed/legacy keys still derive the same bytes so existing
 * encrypted secrets keep decrypting (no data loss after the change).
 */
class SecretCryptoKeyDerivationTest {

    private SecretCrypto crypto(String keyValue) {
        SecretsProperties props = new SecretsProperties();
        props.setKeys(Map.of("k1", keyValue));
        props.setActiveKeyId("k1");
        SecretCrypto c = new SecretCrypto(props);
        c.init();
        return c;
    }

    @Test
    void passphrasePrefix_roundTrips() {
        SecretCrypto c = crypto("passphrase:correct horse battery staple");
        SecretCrypto.Encrypted enc = c.encrypt("top-secret");
        assertEquals("top-secret", c.decrypt(enc.ciphertext(), enc.iv(), enc.keyId()));
    }

    @Test
    void base64Prefix_32Bytes_roundTrips() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        SecretCrypto c = crypto("base64:" + Base64.getEncoder().encodeToString(raw));
        SecretCrypto.Encrypted enc = c.encrypt("hello");
        assertEquals("hello", c.decrypt(enc.ciphertext(), enc.iv(), enc.keyId()));
    }

    @Test
    void base64Prefix_wrongLength_failsFast() {
        String shortKey = "base64:" + Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> crypto(shortKey));
    }

    @Test
    void legacyUnprefixedPassphrase_stillDecrypts() {
        SecretCrypto c = crypto("a-plain-dev-passphrase");
        SecretCrypto.Encrypted enc = c.encrypt("legacy-value");
        assertEquals("legacy-value", c.decrypt(enc.ciphertext(), enc.iv(), enc.keyId()));
    }

    @Test
    void addingPassphrasePrefix_doesNotBreakExistingCiphertext() {
        // A secret encrypted with the bare value must still decrypt after the operator
        // migrates that config value to the explicit 'passphrase:' form.
        String plaintext = "stable-across-migration";
        SecretCrypto.Encrypted enc = crypto("shared-pass").encrypt(plaintext);
        SecretCrypto migrated = crypto("passphrase:shared-pass");
        assertEquals(plaintext, migrated.decrypt(enc.ciphertext(), enc.iv(), enc.keyId()));
    }
}
