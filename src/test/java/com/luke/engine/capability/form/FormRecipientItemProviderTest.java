package com.luke.engine.capability.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luke.engine.recipient.RecipientItem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Forms as the first recipient-portal provider: open instances → "form" items, plus phone resolution. */
class FormRecipientItemProviderTest {

    private final FormInstanceRepository instances = mock(FormInstanceRepository.class);
    private final FormDefinitionRepository forms = mock(FormDefinitionRepository.class);
    private final FormRecipientItemProvider provider = new FormRecipientItemProvider(instances, forms);

    private FormInstance instance(String token, String phone) {
        FormInstance i = new FormInstance();
        i.setTenantId("t1");
        i.setToken(token);
        i.setDefinitionCode("FM-1");
        i.setVersion(1);
        i.setState(FormInstanceStates.SENT);
        i.setRecipient(Map.of("email", "jo@acme.com", "phone", phone));
        return i;
    }

    @Test
    void itemsFor_mapsOpenInstancesToFormItemsWithTheFormName() {
        when(instances.findByTenantIdAndRecipientEmailAndStateInOrderByCreatedAtDesc(eq("t1"), eq("jo@acme.com"), any()))
                .thenReturn(new java.util.ArrayList<>(List.of(instance("inv_1", "+15551234567"))));
        FormDefinition def = new FormDefinition();
        def.setCode("FM-1");
        def.setName("Intake");
        when(forms.findByTenantIdAndCode("t1", "FM-1")).thenReturn(Optional.of(def));

        List<RecipientItem> items = provider.itemsFor("t1", "jo@acme.com");

        assertThat(provider.type()).isEqualTo("form");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).type()).isEqualTo("form");
        assertThat(items.get(0).token()).isEqualTo("inv_1");
        assertThat(items.get(0).title()).isEqualTo("Intake");
        assertThat(items.get(0).status()).isEqualTo(FormInstanceStates.SENT);
    }

    @Test
    void phoneFor_returnsTheFirstInstancePhone() {
        when(instances.findByTenantIdAndRecipientEmailAndStateInOrderByCreatedAtDesc(eq("t1"), eq("jo@acme.com"), any()))
                .thenReturn(new java.util.ArrayList<>(List.of(instance("inv_1", "+15551234567"))));

        assertThat(provider.phoneFor("t1", "jo@acme.com")).contains("+15551234567");
    }

    @Test
    void phoneFor_isEmptyWhenNoInstanceHasAPhone() {
        FormInstance noPhone = new FormInstance();
        noPhone.setTenantId("t1");
        noPhone.setToken("inv_2");
        noPhone.setDefinitionCode("FM-1");
        noPhone.setVersion(1);
        noPhone.setState(FormInstanceStates.SENT);
        noPhone.setRecipient(Map.of("email", "jo@acme.com"));
        when(instances.findByTenantIdAndRecipientEmailAndStateInOrderByCreatedAtDesc(eq("t1"), eq("jo@acme.com"), any()))
                .thenReturn(new java.util.ArrayList<>(List.of(noPhone)));

        assertThat(provider.phoneFor("t1", "jo@acme.com")).isEmpty();
    }
}
