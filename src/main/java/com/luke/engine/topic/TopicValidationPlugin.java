package com.luke.engine.topic;

import org.finos.fluxnova.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class TopicValidationPlugin extends AbstractProcessEnginePlugin {

    private static final Logger log = LoggerFactory.getLogger(TopicValidationPlugin.class);

    private final RegisteredTopicRepository repository;

    @Value("${task-engine.base-url:http://localhost:8090}")
    private String taskEngineUrl;

    public TopicValidationPlugin(RegisteredTopicRepository repository) {
        this.repository = repository;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl config) {
        log.info("Luke Topic Validation Plugin registered (task-engine: {})", taskEngineUrl);

        if (config.getCustomPostBPMNParseListeners() == null) {
            config.setCustomPostBPMNParseListeners(new ArrayList<>());
        }

        config.getCustomPostBPMNParseListeners().add(new TopicValidationParseListener(repository, taskEngineUrl));
    }
}
