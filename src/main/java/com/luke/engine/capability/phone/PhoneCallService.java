package com.luke.engine.capability.phone;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the phone-call lifecycle for both directions, recording every call as a
 * {@link PhoneCall} audit row — mirroring how {@code EmailService} records every send.
 *
 * <ul>
 *   <li><b>Outbound</b> ({@link #placeOutbound}): resolve the tenant's Vapi key + defaults →
 *       persist a QUEUED row and a Camunda outbox row in one transaction → place the call via
 *       {@link VapiClient} → flip the row to RINGING/FAILED. The row is always saved, so a
 *       provider failure is recorded (not thrown).</li>
 *   <li><b>Inbound</b> ({@link #recordInbound}): the {@code assistant-request} webhook resolves
 *       the owning tenant from the dialed number and records the call + outbox row, then routes
 *       the call to the resolved assistant.</li>
 *   <li><b>Lifecycle</b> ({@link #applyStatus}, {@link #applyEndReport}): Vapi's {@code status-update}
 *       and {@code end-of-call-report} webhooks advance the row and, on the end report, stamp the
 *       transcript/recording/summary/cost and mark it terminal. The {@link PhoneCallProcessOutboxConsumer}
 *       then correlates the call-ended message to finish the parked process.</li>
 * </ul>
 */
@Service
public class PhoneCallService {

    private static final Logger log = LoggerFactory.getLogger(PhoneCallService.class);

    private final PhoneCallRepository calls;
    private final PhoneCallProcessOutboxRepository outbox;
    private final PhoneNumberRepository numbers;
    private final PhoneSettingsRepository settings;
    private final VapiCredentials credentials;
    private final VapiClient vapi;

    public PhoneCallService(PhoneCallRepository calls, PhoneCallProcessOutboxRepository outbox,
                            PhoneNumberRepository numbers, PhoneSettingsRepository settings,
                            VapiCredentials credentials, VapiClient vapi) {
        this.calls = calls;
        this.outbox = outbox;
        this.numbers = numbers;
        this.settings = settings;
        this.credentials = credentials;
        this.vapi = vapi;
    }

    /* ── outbound ───────────────────────────────────────────────── */

    /** Place an outbound call and record the outcome. Caller mistakes throw; provider failure is recorded. */
    @Transactional
    public PhoneCall placeOutbound(String tenantId, String createdBy, OutboundCallRequest req) {
        if (req == null || isBlank(req.customerNumber())) throw bad("customerNumber is required");

        PhoneSettings cfg = settings.findById(tenantId).orElse(null);
        String phoneNumberId = firstNonBlank(req.phoneNumberId(), cfg != null ? cfg.getDefaultPhoneNumberId() : null);
        String assistantId = firstNonBlank(req.assistantId(), cfg != null ? cfg.getDefaultAssistantId() : null);
        if (isBlank(phoneNumberId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No phoneNumberId given and no default is configured — provision a number and set a default first");
        }
        if (isBlank(assistantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No assistantId given and no default is configured — set a default assistant first");
        }
        String apiKey = credentials.resolveApiKey(tenantId);
        if (isBlank(apiKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Phone calling is not configured (no Vapi API key for this tenant)");
        }

        PhoneCall call = new PhoneCall();
        call.setTenantId(tenantId);
        call.setDirection(PhoneCallDirection.OUTBOUND);
        call.setStatus(PhoneCallStatus.QUEUED);
        call.setPhoneNumberId(phoneNumberId);
        call.setAssistantId(assistantId);
        call.setCustomerNumber(req.customerNumber().trim());
        call.setMetadata(req.metadata());
        call.setCreatedBy(createdBy);
        persistNewCall(call); // row + outbox in this transaction

        // Place the call. Echo our call id in metadata so we can also correlate from the webhook if needed.
        Map<String, Object> meta = req.metadata() != null ? req.metadata() : Map.of();
        VapiClient.CallResult res = vapi.createCall(apiKey, phoneNumberId, call.getCustomerNumber(),
                assistantId, req.variableValues(), meta);
        if (res.ok()) {
            call.setVapiCallId(res.callId());
            String mapped = PhoneCallStatus.fromVapi(res.status());
            call.setStatus(mapped != null ? mapped : PhoneCallStatus.RINGING);
            call.setStartedAt(LocalDateTime.now());
        } else {
            call.setStatus(PhoneCallStatus.FAILED);
            call.setErrorMessage(res.error());
            call.setEndedAt(LocalDateTime.now());
        }
        return calls.save(call);
    }

    /* ── inbound routing ───────────────────────────────────────── */

    /** Where an inbound call to a dialed number routes: the owning tenant + the answering assistant. */
    public record InboundRouting(String tenantId, String assistantId) {}

    /**
     * Resolve who answers an inbound call to {@code vapiNumberId}: the tenant owning the number and
     * its assistant (the number's own assistant, else the tenant's default). Empty if the number
     * isn't one we know — the webhook then declines to route.
     */
    @Transactional(readOnly = true)
    public Optional<InboundRouting> resolveInboundRouting(String vapiNumberId) {
        if (isBlank(vapiNumberId)) return Optional.empty();
        return numbers.findByVapiNumberId(vapiNumberId).map(num -> {
            String assistantId = num.getAssistantId();
            if (isBlank(assistantId)) {
                assistantId = settings.findById(num.getTenantId())
                        .map(PhoneSettings::getDefaultAssistantId).orElse(null);
            }
            return new InboundRouting(num.getTenantId(), assistantId);
        });
    }

    /**
     * Resolve the tenant for a webhook (e.g. a {@code tool-calls} event): the recorded call's tenant
     * if we have a row, else the owning tenant of the dialed/calling number. Empty if neither resolves.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveTenant(String vapiCallId, String vapiNumberId) {
        if (!isBlank(vapiCallId)) {
            Optional<PhoneCall> c = calls.findByVapiCallId(vapiCallId);
            if (c.isPresent()) return Optional.of(c.get().getTenantId());
        }
        if (!isBlank(vapiNumberId)) {
            return numbers.findByVapiNumberId(vapiNumberId).map(PhoneNumber::getTenantId);
        }
        return Optional.empty();
    }

    /** Record an inbound call (idempotent on the Vapi call id) so it lists alongside outbound calls. */
    @Transactional
    public PhoneCall recordInbound(String tenantId, String vapiCallId, String vapiNumberId,
                                   String customerNumber, String assistantId) {
        Optional<PhoneCall> existing = isBlank(vapiCallId)
                ? Optional.empty() : calls.findByVapiCallId(vapiCallId);
        if (existing.isPresent()) return existing.get();

        PhoneCall call = new PhoneCall();
        call.setTenantId(tenantId);
        call.setDirection(PhoneCallDirection.INBOUND);
        call.setStatus(PhoneCallStatus.RINGING);
        call.setVapiCallId(vapiCallId);
        call.setPhoneNumberId(vapiNumberId);
        call.setAssistantId(assistantId);
        call.setCustomerNumber(customerNumber);
        call.setCreatedBy("vapi");
        call.setStartedAt(LocalDateTime.now());
        persistNewCall(call);
        return call;
    }

    /* ── lifecycle from webhooks ───────────────────────────────── */

    /** Apply a Vapi {@code status-update} to the matching call (no-op if untracked or terminal). */
    @Transactional
    public void applyStatus(String vapiCallId, String vapiStatus) {
        if (isBlank(vapiCallId)) return;
        calls.findByVapiCallId(vapiCallId).ifPresent(call -> {
            if (PhoneCallStatus.isTerminal(call.getStatus())) return;
            String mapped = PhoneCallStatus.fromVapi(vapiStatus);
            if (mapped == null) return;
            call.setStatus(mapped);
            if (PhoneCallStatus.IN_PROGRESS.equals(mapped) && call.getStartedAt() == null) {
                call.setStartedAt(LocalDateTime.now());
            }
            if (PhoneCallStatus.ENDED.equals(mapped) && call.getEndedAt() == null) {
                call.setEndedAt(LocalDateTime.now());
            }
            calls.save(call);
        });
    }

    /** The terminal artifacts Vapi delivers in an end-of-call report. */
    public record EndReport(String endedReason, String transcript, String recordingUrl,
                            String summary, Double cost, Map<String, Object> analysis) {}

    /**
     * Apply a Vapi {@code end-of-call-report}: stamp transcript/recording/summary/cost and mark the
     * call terminal. Idempotent — a duplicate report on an already-terminal call is ignored. The
     * outbox consumer then correlates the call-ended message to finish the parked process.
     */
    @Transactional
    public void applyEndReport(String vapiCallId, EndReport report) {
        if (isBlank(vapiCallId)) return;
        calls.findByVapiCallId(vapiCallId).ifPresentOrElse(call -> {
            if (PhoneCallStatus.isTerminal(call.getStatus())) return; // already settled
            call.setEndedReason(report.endedReason());
            call.setTranscript(report.transcript());
            call.setRecordingUrl(report.recordingUrl());
            call.setSummary(report.summary());
            call.setCost(report.cost());
            call.setAnalysis(report.analysis());
            call.setEndedAt(LocalDateTime.now());
            call.setStatus(isFailureReason(report.endedReason()) ? PhoneCallStatus.FAILED : PhoneCallStatus.ENDED);
            calls.save(call);
        }, () -> log.info("end-of-call-report for untracked Vapi call {} — ignored", vapiCallId));
    }

    /* ── helpers ────────────────────────────────────────────────── */

    /** Save the call and its outbox row together so the Camunda start is driven exactly once. */
    private void persistNewCall(PhoneCall call) {
        calls.save(call);
        call.setBusinessKey(call.getId());
        call.setProcessStatus(PhoneCallProcessOutbox.QUEUED);
        calls.save(call);
        outbox.save(new PhoneCallProcessOutbox(call.getId(), call.getId(), call.getTenantId(), call.getDirection()));
    }

    /** A Vapi ended-reason signals failure when it isn't a normal hang-up/transfer. */
    private static boolean isFailureReason(String reason) {
        if (reason == null) return false;
        String r = reason.toLowerCase();
        return r.contains("error") || r.contains("failed") || r.contains("no-answer")
                || r.contains("busy") || r.contains("voicemail") || r.contains("rejected");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b != null && !b.isBlank() ? b.trim() : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
