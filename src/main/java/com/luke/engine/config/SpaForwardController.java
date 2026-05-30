package com.luke.engine.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards all non-API, non-static routes to index.html for the React SPA.
 * This ensures client-side routing works (e.g. /setup, /login, /dashboard).
 *
 * Excludes:
 * - /engine-rest/** (Camunda REST API)
 * - /api/** (Luke custom APIs)
 * - /actuator/** (Spring Boot actuator)
 * - /h2-console/** (H2 dev console)
 * - Static assets (files with extensions)
 */
@Controller
public class SpaForwardController {

    @RequestMapping("/documentation")
    public String redirectDocs() {
        return "redirect:/documentation/index.html";
    }

    @RequestMapping("/documentation/")
    public String forwardDocsRoot() {
        return "forward:/documentation/index.html";
    }

    @RequestMapping(value = {
        "/", "/setup", "/login", "/dashboard",
        "/processes", "/processes/**",
        "/tasks", "/tasks/**",
        "/incidents", "/incidents/**",
        "/jobs", "/jobs/**",
        "/history", "/history/**",
        "/deployments", "/deployments/**",
        "/decisions", "/decisions/**",
        "/admin/**",
        "/settings",
        "/database",
        "/calendars", "/calendars/**",
        "/external-tasks", "/external-tasks/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
