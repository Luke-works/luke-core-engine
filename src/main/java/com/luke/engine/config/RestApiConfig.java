package com.luke.engine.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RestApiConfig {

    private static final Logger log = LoggerFactory.getLogger(RestApiConfig.class);

    @Value("${server.port:8080}")
    private int serverPort;

    @PostConstruct
    public void logEndpoints() {
        log.info("========================================");
        log.info("  Luke Core Engine started");
        log.info("  Dashboard: http://localhost:{}", serverPort);
        log.info("  REST API:  http://localhost:{}/engine-rest", serverPort);
        log.info("  Health:    http://localhost:{}/actuator/health", serverPort);
        log.info("========================================");
    }
}
