package com.luke.engine.topic;

import org.finos.fluxnova.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.finos.fluxnova.bpm.engine.impl.pvm.process.ActivityImpl;
import org.finos.fluxnova.bpm.engine.impl.pvm.process.ScopeImpl;
import org.finos.fluxnova.bpm.engine.impl.util.xml.Element;
import org.finos.fluxnova.bpm.engine.impl.util.xml.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * BPMN parse listener that validates external task topics against the
 * Topic Registry in luke-task-engine. If the task engine is unavailable,
 * falls back to the local repository.
 */
public class TopicValidationParseListener extends AbstractBpmnParseListener {

    private static final Logger log = LoggerFactory.getLogger(TopicValidationParseListener.class);
    private static final Namespace CAMUNDA_NS = new Namespace("http://camunda.org/schema/1.0/bpmn");

    private final RegisteredTopicRepository localRepo;
    private final String taskEngineUrl;

    public TopicValidationParseListener(RegisteredTopicRepository localRepo, String taskEngineUrl) {
        this.localRepo = localRepo;
        this.taskEngineUrl = taskEngineUrl;
    }

    @Override
    public void parseServiceTask(Element element, ScopeImpl scope, ActivityImpl activity) {
        String type = element.attributeNS(CAMUNDA_NS, "type");
        if (!"external".equalsIgnoreCase(type)) return;

        String topic = element.attributeNS(CAMUNDA_NS, "topic");
        if (topic == null || topic.isBlank()) return;

        boolean registered = isTopicRegistered(topic);

        if (!registered) {
            String msg = String.format(
                "Deployment rejected: External task topic '%s' on activity '%s' is not registered. " +
                "Register the topic in the Task Engine Topic Registry before deploying.",
                topic, activity.getId()
            );
            log.error(msg);
            throw new RuntimeException(msg);
        }

        log.debug("External task topic '{}' on activity '{}' is registered", topic, activity.getId());
    }

    private boolean isTopicRegistered(String topicName) {
        // Try task-engine API first
        try {
            RestTemplate rest = new RestTemplate();
            @SuppressWarnings("unchecked")
            Map<String, Boolean> result = rest.getForObject(
                taskEngineUrl + "/api/topics/validate?topicName=" + topicName,
                Map.class
            );
            if (result != null && result.containsKey("registered")) {
                return result.get("registered");
            }
        } catch (Exception e) {
            log.debug("Task engine unavailable for topic validation, falling back to local: {}", e.getMessage());
        }

        // Fallback to local repository
        return localRepo.findByTopicName(topicName)
                .map(RegisteredTopic::isActive)
                .orElse(false);
    }
}
