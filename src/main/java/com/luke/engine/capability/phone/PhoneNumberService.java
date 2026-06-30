package com.luke.engine.capability.phone;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns a tenant's Vapi phone numbers: provisioning a Vapi-provided number or importing a BYO
 * carrier number, and listing what the tenant has. Provisioning is an explicit action (not
 * best-effort like a call placement): a Vapi failure is a hard error so the caller sees why
 * nothing was created. The provider credentials for an imported number stay in Vapi.
 */
@Service
public class PhoneNumberService {

    private static final Logger log = LoggerFactory.getLogger(PhoneNumberService.class);

    private final PhoneNumberRepository numbers;
    private final VapiCredentials credentials;
    private final VapiClient vapi;

    public PhoneNumberService(PhoneNumberRepository numbers, VapiCredentials credentials, VapiClient vapi) {
        this.numbers = numbers;
        this.credentials = credentials;
        this.vapi = vapi;
    }

    public List<PhoneNumber> list(String tenantId) {
        return numbers.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public PhoneNumber get(String tenantId, String id) {
        return numbers.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown phone number: " + id));
    }

    /** Request to provision a Vapi-provided number, or import a BYO carrier number. */
    public record ProvisionRequest(String provider, String areaCode, String number,
                                   String credentialId, String name, String assistantId) {}

    /** Provision a Vapi number or import a carrier number, then persist the mapping. */
    public PhoneNumber provision(String tenantId, ProvisionRequest req) {
        String apiKey = requireApiKey(tenantId);
        String provider = req.provider() == null || req.provider().isBlank() ? "vapi" : req.provider().trim().toLowerCase();

        VapiClient.NumberResult res;
        if ("vapi".equals(provider)) {
            res = vapi.buyVapiNumber(apiKey, req.areaCode(), req.name(), req.assistantId());
        } else {
            if (req.number() == null || req.number().isBlank()) {
                throw bad("number is required to import a " + provider + " number");
            }
            res = vapi.importNumber(apiKey, provider, req.number(), req.credentialId(), req.name(), req.assistantId());
        }
        if (!res.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Vapi could not provision the number: " + res.error());
        }

        PhoneNumber num = new PhoneNumber();
        num.setTenantId(tenantId);
        num.setVapiNumberId(res.numberId());
        num.setNumber(res.number() != null ? res.number() : req.number());
        num.setProvider(res.provider() != null ? res.provider() : provider);
        num.setName(req.name());
        num.setAssistantId(req.assistantId());
        numbers.save(num);
        log.info("Provisioned Vapi number {} (id {}, {}) for tenant {}",
                num.getNumber(), num.getVapiNumberId(), num.getProvider(), tenantId);
        return num;
    }

    private String requireApiKey(String tenantId) {
        String apiKey = credentials.resolveApiKey(tenantId);
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Phone calling is not configured (no Vapi API key for this tenant)");
        }
        return apiKey;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
