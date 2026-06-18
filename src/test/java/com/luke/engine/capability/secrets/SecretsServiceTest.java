package com.luke.engine.capability.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link SecretsService}: encryption round-trip, masking, the
 * SYSTEM-vs-TENANT boundary (the tenant ops must never touch SYSTEM secrets),
 * conflict on create, rotation, and managedBy preservation on overwrite. Backed by
 * a real {@link SecretCrypto} and an in-memory mock repository.
 */
class SecretsServiceTest {

    private static final String TENANT = "TEN-TST-01JAN26";

    private final Map<String, Secret> store = new HashMap<>();
    private SecretsService secrets;

    private static String key(String tenantId, String name) {
        return tenantId + "|" + name;
    }

    @BeforeEach
    void setUp() {
        SecretRepository repo = mock(SecretRepository.class);

        when(repo.findByTenantIdAndName(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(store.get(key(inv.getArgument(0), inv.getArgument(1)))));
        when(repo.existsByTenantIdAndName(any(), any())).thenAnswer(inv ->
                store.containsKey(key(inv.getArgument(0), inv.getArgument(1))));
        when(repo.save(any())).thenAnswer(inv -> {
            Secret s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID().toString());
            store.put(key(s.getTenantId(), s.getName()), s);
            return s;
        });
        doAnswer(inv -> {
            Secret s = inv.getArgument(0);
            store.remove(key(s.getTenantId(), s.getName()));
            return null;
        }).when(repo).delete(any());
        when(repo.findByTenantIdAndManagedByOrderByCreatedAtDesc(any(), any())).thenAnswer(inv ->
                store.values().stream()
                        .filter(s -> s.getTenantId().equals(inv.getArgument(0)))
                        .filter(s -> s.getManagedBy().equals(inv.getArgument(1)))
                        .toList());
        when(repo.findByTenantIdOrderByCreatedAtDesc(any())).thenAnswer(inv ->
                store.values().stream().filter(s -> s.getTenantId().equals(inv.getArgument(0))).toList());

        SecretsProperties props = new SecretsProperties();
        props.setActiveKeyId("v1");
        props.setKeys(Map.of("v1", "unit-test-master-key"));
        SecretCrypto crypto = new SecretCrypto(props);
        crypto.init();

        secrets = new SecretsService(repo, crypto);
    }

    @Test
    void storeThenGetReturnsPlaintext() {
        secrets.put(TENANT, "postmark.server-token", "pm_live_abcd", ManagedBy.SYSTEM);

        assertThat(secrets.get(TENANT, "postmark.server-token")).contains("pm_live_abcd");
        // Stored encrypted, not in the clear.
        assertThat(store.get(key(TENANT, "postmark.server-token")).getCiphertext()).doesNotContain("pm_live_abcd");
    }

    @Test
    void getMissingReturnsEmpty() {
        assertThat(secrets.get(TENANT, "nope")).isEmpty();
    }

    @Test
    void createListsMaskedAndHidesPlaintext() {
        SecretsService.SecretView view = secrets.create(TENANT, "stripe.api-key", "sk_live_wxyz1234", "Stripe", "user-1");

        assertThat(view.maskedValue()).isEqualTo("••••1234");
        assertThat(view.managedBy()).isEqualTo(ManagedBy.TENANT);
        assertThat(view.version()).isEqualTo(1);
        // The view type carries no plaintext field at all.
        assertThat(secrets.list(TENANT)).extracting(SecretsService.SecretView::name).containsExactly("stripe.api-key");
    }

    @Test
    void createRejectsDuplicateName() {
        secrets.create(TENANT, "dup", "v1", null, null);
        assertThatThrownBy(() -> secrets.create(TENANT, "dup", "v2", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void rotateChangesValueAndBumpsVersion() {
        secrets.create(TENANT, "rot", "old-value", null, null);
        SecretsService.SecretView rotated = secrets.rotate(TENANT, "rot", "new-value-1234");

        assertThat(rotated.version()).isEqualTo(2);
        assertThat(rotated.maskedValue()).isEqualTo("••••1234");
        assertThat(secrets.get(TENANT, "rot")).contains("new-value-1234");
    }

    @Test
    void tenantOpsCannotTouchSystemSecrets() {
        secrets.put(TENANT, "postmark.server-token", "pm_live_secret", ManagedBy.SYSTEM);

        // Not listed among the tenant's own secrets …
        assertThat(secrets.list(TENANT)).isEmpty();
        // … and rotate/delete refuse it as "not found".
        assertThatThrownBy(() -> secrets.rotate(TENANT, "postmark.server-token", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> secrets.deleteTenantSecret(TENANT, "postmark.server-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        // But it IS visible to the internal/admin listing.
        assertThat(secrets.listAll(TENANT)).hasSize(1);
    }

    @Test
    void overwritePreservesManagedByClass() {
        secrets.put(TENANT, "shared", "first", ManagedBy.SYSTEM);
        secrets.put(TENANT, "shared", "second", ManagedBy.TENANT); // attempt to reclassify

        assertThat(store.get(key(TENANT, "shared")).getManagedBy()).isEqualTo(ManagedBy.SYSTEM);
        assertThat(secrets.get(TENANT, "shared")).contains("second");
    }

    @Test
    void deletePortReportsWhetherItExisted() {
        secrets.put(TENANT, "temp", "v", ManagedBy.SYSTEM);
        assertThat(secrets.delete(TENANT, "temp")).isTrue();
        assertThat(secrets.delete(TENANT, "temp")).isFalse();
    }
}
