package com.luke.engine.capability.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Seeds the baseline capability catalog on first start so the platform has
 * something to serve. Idempotent — only inserts capabilities that are missing.
 */
@Component
public class CapabilitySeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySeed.class);

    private final CapabilityRepository repository;

    public CapabilitySeed(CapabilityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(new Capability("CALENDAR", "Business Calendars", "Working calendars, holidays and time windows.", "CalendarDays", "/calendars", "ACTIVE", "STANDARD"));
        seed(new Capability("SLA", "SLA Management", "Service level targets and breach tracking.", "Timer", "/sla", "ACTIVE", "PREMIUM"));
        seed(new Capability("FORMS", "Forms", "Build and manage forms.", "ListChecks", "/forms", "ACTIVE", "STANDARD"));
        seed(new Capability("EMAIL", "Email", "Send transactional email via Postmark.", "Mail", "/emails", "ACTIVE", "STANDARD"));
        // SECRETS is internal-only for now — used by services via /api/internal/secrets,
        // not offered to tenants. Re-add a seed here when the tenant API is opened.
    }

    private void seed(Capability capability) {
        if (!repository.existsByCode(capability.getCode())) {
            repository.save(capability);
            log.info("Seeded capability {}", capability.getCode());
        }
    }
}
