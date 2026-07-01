package com.luke.engine.capability.phone;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages a tenant's Vapi phone numbers, guarded by the PHONE capability (read to list, write to
 * provision). Provisioning calls Vapi (buy a Vapi number or import a BYO carrier number) and stores
 * the resulting mapping. Tenant-scoped via {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/phone-numbers")
public class PhoneNumberController {

    private final PhoneNumberService numbers;

    public PhoneNumberController(PhoneNumberService numbers) {
        this.numbers = numbers;
    }

    /** Provision a Vapi number (provider="vapi") or import a carrier number (twilio/telnyx/vonage). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PhoneNumber provision(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestBody PhoneNumberService.ProvisionRequest body) {
        requireTenant(tenantId);
        return numbers.provision(tenantId, body);
    }

    @GetMapping
    public List<PhoneNumber> list(@RequestHeader("X-Tenant-Id") String tenantId) {
        requireTenant(tenantId);
        return numbers.list(tenantId);
    }

    @GetMapping("/{id}")
    public PhoneNumber get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        requireTenant(tenantId);
        return numbers.get(tenantId, id);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }
}
