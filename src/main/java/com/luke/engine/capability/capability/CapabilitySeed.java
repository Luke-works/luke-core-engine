package com.luke.engine.capability.capability;

import com.luke.engine.config.BootCoordinator;
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
    private final BootCoordinator bootCoordinator;

    public CapabilitySeed(CapabilityRepository repository, BootCoordinator bootCoordinator) {
        this.repository = repository;
        this.bootCoordinator = bootCoordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        // #40: serialize across instances so the seed doesn't race on first boot.
        bootCoordinator.runExclusive("capability-seed", this::seedAll);
    }

    private void seedAll() {
        seed(new Capability("CALENDAR", "Business Calendars", "Working calendars, holidays and time windows.", "CalendarDays", "/calendars", "ACTIVE", "STANDARD"));
        seed(new Capability("SLA", "SLA Management", "Service level targets and breach tracking.", "Timer", "/sla", "ACTIVE", "PREMIUM"));
        seed(new Capability("FORMS", "Forms", "Build and manage forms.", "ListChecks", "/forms", "ACTIVE", "STANDARD"));
        seed(new Capability("EMAIL", "Email", "Send transactional email via Postmark.", "Mail", "/emails", "ACTIVE", "STANDARD"));
        seed(new Capability("SIGNATURES", "Signatures", "Send documents for signature.", "PenLine", "/signatures", "ACTIVE", "STANDARD"));
        seed(new Capability("PHONE", "Phone / Voice", "Inbound & outbound voice calls via Vapi.", "Phone", "/phone", "ACTIVE", "STANDARD"));
        seed(new Capability("WORKFLOW", "Workflow", "Compose capabilities into automated processes with integrations.", "Workflow", "/workflow", "ACTIVE", "PREMIUM"));
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
