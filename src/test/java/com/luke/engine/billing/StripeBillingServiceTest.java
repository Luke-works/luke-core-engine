package com.luke.engine.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.audit.AdminAuditService;
import com.luke.engine.branding.PlanCatalog;
import com.luke.engine.branding.PlanService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The webhook → plan mapping (the part that must be right for a paid tenant to actually get their
 * tier) and the checkout guards that run before any Stripe network call. Signature verification and
 * the real Session creation need a live/mocked Stripe and aren't exercised here.
 */
class StripeBillingServiceTest {

    private final BillingCustomerRepository customers = mock(BillingCustomerRepository.class);
    private final PlanService plans = mock(PlanService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /** Fully-configured Stripe: secret + webhook secret + both price ids. */
    private BillingProperties configured() {
        return new BillingProperties("sk_test_x", "whsec_x", "price_pro", "price_biz",
                "https://app/plans?ok", "https://app/plans?no");
    }

    /** No secret key → the module is off. */
    private BillingProperties disabled() {
        return new BillingProperties("", "", "", "", "https://app/ok", "https://app/no");
    }

    private StripeBillingService svc(BillingProperties props) {
        return new StripeBillingService(props, customers, plans, audit, mapper);
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ── config gating ────────────────────────────────────────────────────── */

    @Test
    void disabledWhenNoSecretKey() {
        StripeBillingService svc = svc(disabled());
        assertThat(svc.enabled()).isFalse();
        assertThat(svc.purchasableTiers()).isEmpty();
        assertThat(svc.handleWebhook("{}", "sig")).isNull(); // ignored, never throws
    }

    @Test
    void purchasableTiersAreThoseWithAConfiguredPrice() {
        assertThat(svc(configured()).purchasableTiers()).containsExactly("PRO", "BUSINESS");
        BillingProperties proOnly =
                new BillingProperties("sk_x", "whsec_x", "price_pro", "", "u", "u");
        assertThat(svc(proOnly).purchasableTiers()).containsExactly("PRO");
    }

    @Test
    void checkoutRefusesWhenBillingDisabled() {
        assertThatThrownBy(() -> svc(disabled()).createCheckoutSession("t1", "PRO"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void checkoutRefusesANonPurchasableTierBeforeAnyNetworkCall() {
        StripeBillingService svc = svc(configured());
        for (String tier : new String[] {"FREE", "ENTERPRISE", "NONSENSE"}) {
            assertThatThrownBy(() -> svc.createCheckoutSession("t1", tier))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
    }

    /* ── webhook → plan seam ──────────────────────────────────────────────── */

    @Test
    void checkoutCompletedUpgradesTheTenantAndRemembersTheCustomer() {
        svc(configured()).processEvent("checkout.session.completed",
                json("{\"metadata\":{\"tenantId\":\"t1\",\"tier\":\"PRO\"},\"customer\":\"cus_1\",\"subscription\":\"sub_1\"}"));

        verify(plans).applyPlan("t1", PlanCatalog.PRO, "stripe:sub_1");
        verify(customers).save(any(BillingCustomer.class));
    }

    @Test
    void checkoutCompletedWithNoTenantReferenceIsIgnored() {
        svc(configured()).processEvent("checkout.session.completed", json("{\"customer\":\"cus_1\"}"));
        verify(plans, never()).applyPlan(any(), any(), any());
    }

    @Test
    void subscriptionUpdatedMapsThePriceToTheTier() {
        svc(configured()).processEvent("customer.subscription.updated",
                json("{\"id\":\"sub_1\",\"customer\":\"cus_1\",\"status\":\"active\",\"metadata\":{\"tenantId\":\"t1\"},"
                        + "\"items\":{\"data\":[{\"price\":{\"id\":\"price_biz\"}}]}}"));

        verify(plans).applyPlan("t1", PlanCatalog.BUSINESS, "stripe:sub_1");
    }

    @Test
    void subscriptionWithAnUnknownPriceLeavesThePlanUnchanged() {
        svc(configured()).processEvent("customer.subscription.updated",
                json("{\"id\":\"sub_1\",\"customer\":\"cus_1\",\"status\":\"active\",\"metadata\":{\"tenantId\":\"t1\"},"
                        + "\"items\":{\"data\":[{\"price\":{\"id\":\"price_mystery\"}}]}}"));

        verify(plans, never()).applyPlan(any(), any(), any());
    }

    @Test
    void subscriptionDeletedDowngradesToFree() {
        svc(configured()).processEvent("customer.subscription.deleted",
                json("{\"id\":\"sub_1\",\"customer\":\"cus_1\",\"metadata\":{\"tenantId\":\"t1\"}}"));

        verify(plans).applyPlan(eq("t1"), eq(PlanCatalog.FREE), isNull());
    }

    @Test
    void aLapsedStatusAlsoDowngradesToFree() {
        svc(configured()).processEvent("customer.subscription.updated",
                json("{\"id\":\"sub_1\",\"customer\":\"cus_1\",\"status\":\"canceled\",\"metadata\":{\"tenantId\":\"t1\"},"
                        + "\"items\":{\"data\":[{\"price\":{\"id\":\"price_biz\"}}]}}"));

        verify(plans).applyPlan(eq("t1"), eq(PlanCatalog.FREE), isNull());
    }

    @Test
    void tenantIsResolvedFromTheCustomerMappingWhenMetadataIsAbsent() {
        when(customers.findByStripeCustomerId("cus_1"))
                .thenReturn(Optional.of(new BillingCustomer("t1", "cus_1")));

        svc(configured()).processEvent("customer.subscription.deleted",
                json("{\"id\":\"sub_1\",\"customer\":\"cus_1\"}")); // no metadata.tenantId

        verify(plans).applyPlan(eq("t1"), eq(PlanCatalog.FREE), isNull());
    }

    @Test
    void unknownEventTypesAreIgnored() {
        svc(configured()).processEvent("invoice.paid", json("{\"customer\":\"cus_1\"}"));
        verify(plans, never()).applyPlan(any(), any(), any());
    }
}
