package com.luke.engine.capability.secrets;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Master-key configuration for the secrets store. Holds one or more named keys and
 * the id of the active one used for NEW encryptions. Old rows keep their own
 * {@code keyId}, so you can add a fresh key, flip {@code activeKeyId}, and re-encrypt
 * gradually — never a big-bang rotation.
 *
 * <p>Bind via {@code luke.secrets.active-key-id} and {@code luke.secrets.keys.<id>}.
 * In prod set {@code LUKE_SECRETS_ACTIVE_KEY_ID=v1} and {@code LUKE_SECRETS_KEYS_V1=<key>}.
 * A key value may be a base64 32-byte AES key, or any passphrase (SHA-256 derives the
 * 32 bytes) — see {@link SecretCrypto}.
 */
@Component
@ConfigurationProperties(prefix = "luke.secrets")
public class SecretsProperties {

    /** Id of the key used to encrypt new secrets. Must exist in {@link #keys}. */
    private String activeKeyId = "dev";

    /** keyId → key material (base64 32-byte key or a passphrase). */
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getActiveKeyId() { return activeKeyId; }
    public void setActiveKeyId(String activeKeyId) { this.activeKeyId = activeKeyId; }

    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }
}
