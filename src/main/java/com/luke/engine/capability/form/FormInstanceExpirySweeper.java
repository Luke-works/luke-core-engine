package com.luke.engine.capability.form;

import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically marks open-but-past-expiry form instances as EXPIRED (#54), so an
 * abandoned form leaves the open set even if it's never touched again. Enforcement
 * on save/submit (FormInstanceController) handles the touched case; this is the sweep
 * for the rest. Bounded batch per run.
 */
@Component
public class FormInstanceExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(FormInstanceExpirySweeper.class);
    private static final int BATCH = 500;

    private final FormInstanceRepository instances;

    public FormInstanceExpirySweeper(FormInstanceRepository instances) {
        this.instances = instances;
    }

    @Scheduled(
            fixedDelayString = "${luke.forms.expiry-sweep-ms:300000}",
            initialDelayString = "${luke.forms.expiry-sweep-ms:300000}")
    @Transactional
    public void sweep() {
        List<FormInstance> expired = instances.findByStateInAndExpiresAtBefore(
                FormInstanceStates.OPEN, LocalDateTime.now(), Limit.of(BATCH));
        if (expired.isEmpty()) {
            return;
        }
        for (FormInstance inst : expired) {
            inst.setState(FormInstanceStates.EXPIRED);
        }
        instances.saveAll(expired);
        log.info("Expiry sweep: marked {} form instance(s) EXPIRED", expired.size());
    }
}
