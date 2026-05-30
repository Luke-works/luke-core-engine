package com.luke.engine.config;

import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class CamundaConfig {

    private static final Logger log = LoggerFactory.getLogger(CamundaConfig.class);

    @PostConstruct
    public void init() {
        log.info("Luke Core Engine - CIBSeven configuration initialized");
    }
}
