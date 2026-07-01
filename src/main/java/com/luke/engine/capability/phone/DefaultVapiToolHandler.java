package com.luke.engine.capability.phone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fallback {@link VapiToolHandler}: it acknowledges any tool call as "not configured" rather
 * than fabricating an answer, so the {@code tool-calls} contract is satisfied (every tool-call id
 * gets a result) without misleading the assistant. Registered only when no other
 * {@link VapiToolHandler} bean is defined, so a tenant can override it by providing their own.
 */
@Configuration
public class DefaultVapiToolHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultVapiToolHandler.class);

    @Bean
    @ConditionalOnMissingBean(VapiToolHandler.class)
    public VapiToolHandler vapiToolHandler() {
        return (tenantId, toolName, arguments, vapiCallId) -> {
            log.info("Unhandled Vapi tool '{}' on call {} (tenant {}) — no tool handler configured",
                    toolName, vapiCallId, tenantId);
            return VapiToolHandler.ToolResult.error(
                    "The tool '" + toolName + "' is not configured on the server.");
        };
    }
}
