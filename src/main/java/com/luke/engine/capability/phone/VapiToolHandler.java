package com.luke.engine.capability.phone;

import java.util.Map;

/**
 * Extension point for mid-call tool/function calls. When the voice assistant invokes a custom tool,
 * Vapi POSTs a {@code tool-calls} webhook and expects a synchronous result per tool-call id (within
 * the ~7.5s server window). A bean implementing this interface lets a tenant wire business tools
 * (look up a customer, book a slot, create a task) the assistant can call.
 *
 * <p>The default {@link DefaultVapiToolHandler} returns "not configured" for every tool so the
 * contract is satisfied without fabricating answers; replace it with a real handler to add behavior.
 */
public interface VapiToolHandler {

    /** The result for one tool call. ok=false → an {@code error} is returned to the assistant instead. */
    record ToolResult(boolean ok, Object result, String error) {
        public static ToolResult of(Object result) { return new ToolResult(true, result, null); }
        public static ToolResult error(String error) { return new ToolResult(false, null, error); }
    }

    /**
     * Handle one tool invocation.
     *
     * @param tenantId the tenant owning the call (resolved from the dialed/calling number)
     * @param toolName the tool/function name as defined on the Vapi assistant
     * @param arguments the parsed arguments the assistant supplied
     * @param vapiCallId the Vapi call id this invocation belongs to
     */
    ToolResult handle(String tenantId, String toolName, Map<String, Object> arguments, String vapiCallId);
}
