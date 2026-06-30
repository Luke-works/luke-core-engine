package com.luke.engine.capability.phone;

import java.util.Map;

/**
 * Request to place an outbound call, shared by the tenant-facing and internal controllers.
 * {@code customerNumber} is required (E.164). {@code phoneNumberId} and {@code assistantId}
 * fall back to the tenant's {@link PhoneSettings} defaults when blank. {@code variableValues}
 * populate {@code {{template}}} variables in the assistant; {@code metadata} is stored on the
 * call and echoed back on every Vapi webhook (use it to carry process/form linkage).
 *
 * @param customerNumber  the number to call, E.164 (required)
 * @param phoneNumberId   Vapi phone-number id to call from (defaults to the tenant default)
 * @param assistantId     Vapi assistant id to place the call (defaults to the tenant default)
 * @param variableValues  dynamic {{variables}} for the assistant prompt/first message
 * @param metadata        caller linkage stored on the call + echoed on webhooks
 */
public record OutboundCallRequest(
        String customerNumber,
        String phoneNumberId,
        String assistantId,
        Map<String, Object> variableValues,
        Map<String, Object> metadata) {}
