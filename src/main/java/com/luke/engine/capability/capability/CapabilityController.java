package com.luke.engine.capability.capability;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capability catalog (global, platform-wide). Addressed by the stable business
 * {@code code} (e.g. CALENDAR), not the internal UUID. Per-tenant enablement is
 * handled separately by {@link SubscriptionController}.
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityRepository repository;

    public CapabilityController(CapabilityRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Capability> list(@RequestParam(required = false) String status) {
        return status == null ? repository.findAll() : repository.findAllByStatus(status);
    }

    @GetMapping("/{code}")
    public Capability get(@PathVariable String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown capability: " + code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Capability create(@RequestBody Capability body) {
        requireCode(body);
        if (repository.existsByCode(body.getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capability already exists: " + body.getCode());
        }
        Capability capability = new Capability();
        capability.setCode(body.getCode());
        apply(capability, body);
        return repository.save(capability);
    }

    /** Idempotent create-or-replace by code. Code is immutable once set. */
    @PutMapping("/{code}")
    public Capability upsert(@PathVariable String code, @RequestBody Capability body) {
        Capability capability = repository.findByCode(code).orElseGet(() -> {
            Capability fresh = new Capability();
            fresh.setCode(code);
            return fresh;
        });
        apply(capability, body);
        return repository.save(capability);
    }

    /**
     * Soft delete by default (status → RETIRED), keeping the row so tenant
     * subscriptions that reference it stay intact. Pass {@code ?hard=true} to
     * remove the row entirely.
     */
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code,
                                       @RequestParam(defaultValue = "false") boolean hard) {
        Capability capability = repository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown capability: " + code));
        if (hard) {
            repository.delete(capability);
        } else {
            capability.setStatus("RETIRED");
            repository.save(capability);
        }
        return ResponseEntity.noContent().build();
    }

    private void requireCode(Capability body) {
        if (body.getCode() == null || body.getCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");
        }
    }

    /** Copy mutable catalog fields from request onto the managed entity; never touches code/id/createdAt. */
    private void apply(Capability target, Capability src) {
        if (src.getName() != null) target.setName(src.getName());
        target.setDescription(src.getDescription());
        target.setIcon(src.getIcon());
        target.setRoute(src.getRoute());
        if (src.getStatus() != null) target.setStatus(src.getStatus());
        if (src.getTier() != null) target.setTier(src.getTier());
    }
}
