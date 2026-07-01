package com.luke.engine.workflow.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luke.engine.workflow.CapabilityActionException;
import com.luke.engine.workflow.CapabilityActionHandler;
import com.luke.engine.workflow.WorkflowDoc;
import com.luke.engine.workflow.WorkflowNode;
import com.luke.engine.workflow.WorkflowVersion;
import com.luke.engine.workflow.WorkflowVersionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The outbound rail (WF-11): the JavaDelegate every workflow <b>action</b> service task is
 * bound to ({@code ${connectorExecutor}}). It resolves the authoring node from the running
 * process, invokes the capability (Nango for integrations), stores the result, and meters
 * the execution — classifying failures so the engine does the right thing:
 *
 * <ul>
 *   <li>auth/expired → mark the connection NEEDS_RECONNECT + BPMN error {@code connection-error}
 *       (routes to the workflow's reconnect/fallback path).</li>
 *   <li>rate-limit / server / timeout → rethrow so Camunda retries the job (asyncBefore).</li>
 *   <li>business 4xx → BPMN error {@code action-error} (routes to fallback), no retry.</li>
 * </ul>
 *
 * <p>Binding resolution uses the BPMN element id, which equals the authoring node id
 * (WF-3), so no capability metadata is embedded in the BPMN — we look the node up in the
 * deployed {@link WorkflowVersion}'s JSON by the running {@code activityId}.
 */
@Component("connectorExecutor")
public class ConnectorExecutor implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ConnectorExecutor.class);

    static final String ERR_CONNECTION = "connection-error";
    static final String ERR_ACTION = "action-error";
    static final String ERR_BINDING = "binding-error";

    private final WorkflowVersionRepository versions;
    private final IntegrationConnectionRepository connections;
    private final ConnectionService connectionService;
    private final NangoClient nango;
    private final UsageEmitter usage;
    private final ObjectMapper mapper;
    private final Map<String, CapabilityActionHandler> handlers;

    public ConnectorExecutor(WorkflowVersionRepository versions, IntegrationConnectionRepository connections,
            ConnectionService connectionService, NangoClient nango, UsageEmitter usage, ObjectMapper mapper,
            List<CapabilityActionHandler> handlerBeans) {
        this.versions = versions;
        this.connections = connections;
        this.connectionService = connectionService;
        this.nango = nango;
        this.usage = usage;
        this.mapper = mapper;
        this.handlers = new HashMap<>();
        for (CapabilityActionHandler h : handlerBeans) {
            this.handlers.put(h.capability().toLowerCase(Locale.ROOT), h);
        }
    }

    @Override
    public void execute(DelegateExecution execution) {
        String nodeId = execution.getCurrentActivityId();
        String tenantId = execution.getTenantId();

        WorkflowNode node = resolveNode(execution.getProcessDefinitionId(), nodeId);
        if (node == null || node.action() == null) {
            throw new BpmnError(ERR_BINDING, "No connector binding for node '" + nodeId + "'");
        }

        // The outbound rail dispatches by capability. Integrations run through Nango (below);
        // every other capability dispatches to its registered CapabilityActionHandler. A
        // capability with no handler yet completes as a no-op (so the workflow still runs).
        if (!"integrations".equalsIgnoreCase(node.capability())) {
            CapabilityActionHandler handler =
                    node.capability() == null ? null : handlers.get(node.capability().toLowerCase(Locale.ROOT));
            if (handler == null) {
                log.info("No outbound handler for capability '{}' (node '{}') — completing as no-op",
                        node.capability(), node.id());
                return;
            }
            try {
                Object result = handler.execute(tenantId, node, execution.getVariables());
                if (node.output() != null && !node.output().isBlank() && result != null) {
                    execution.setVariable(node.output(), result);
                }
            } catch (CapabilityActionException e) {
                throw new BpmnError(ERR_ACTION, e.getMessage());
            }
            return;
        }

        IntegrationConnection conn = resolveConnection(tenantId, node);

        Map<String, Object> input = node.input() != null ? node.input() : Map.of();

        try {
            Map<String, Object> result =
                    nango.triggerAction(node.provider(), conn.getNangoConnectionId(), node.action(), input);
            if (node.output() != null && !node.output().isBlank()) {
                execution.setVariable(node.output(), result);
            }
            usage.emitExecution(tenantId, conn.getId(), execution.getProcessInstanceId() + ":" + nodeId);
        } catch (NangoAuthException e) {
            connectionService.markNeedsReconnect(conn.getId(), e.getMessage());
            throw new BpmnError(ERR_CONNECTION, e.getMessage());
        } catch (NangoActionException e) {
            throw new BpmnError(ERR_ACTION, e.getMessage());
        }
        // NangoRateLimitException / NangoServerException propagate → Camunda retries the job.
    }

    private WorkflowNode resolveNode(String processDefinitionId, String nodeId) {
        String processId = processKey(processDefinitionId);
        if (processId == null) return null;
        WorkflowVersion version = versions.findFirstByProcessId(processId).orElse(null);
        if (version == null) return null;
        try {
            WorkflowDoc doc = mapper.readValue(version.getJsonSource(), WorkflowDoc.class);
            if (doc.nodes() == null) return null;
            return doc.nodes().stream().filter(n -> nodeId.equals(n.id())).findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve node '{}' for process '{}': {}", nodeId, processId, e.getMessage());
            return null;
        }
    }

    private IntegrationConnection resolveConnection(String tenantId, WorkflowNode node) {
        IntegrationConnection conn;
        String selector = node.connection();
        if (selector != null && !selector.isBlank()) {
            conn = connections.findByIdAndTenantId(selector, tenantId).orElse(null);
        } else {
            conn = connections.findFirstByTenantIdAndProviderKeyAndStatus(
                    tenantId, node.provider(), IntegrationConnectionStatus.ACTIVE).orElse(null);
        }
        if (conn == null) {
            throw new BpmnError(ERR_CONNECTION, "No connection for provider '" + node.provider() + "'");
        }
        if (conn.getStatus() != IntegrationConnectionStatus.ACTIVE || conn.getNangoConnectionId() == null) {
            throw new BpmnError(ERR_CONNECTION, "Connection '" + conn.getId() + "' is not active");
        }
        return conn;
    }

    /** Camunda process definition ids are {@code key:version:id}; the key is our processId. */
    private static String processKey(String processDefinitionId) {
        if (processDefinitionId == null) return null;
        int i = processDefinitionId.indexOf(':');
        return i > 0 ? processDefinitionId.substring(0, i) : processDefinitionId;
    }
}
