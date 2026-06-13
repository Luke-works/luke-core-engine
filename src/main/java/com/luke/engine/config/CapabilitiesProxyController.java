package com.luke.engine.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Enumeration;

/**
 * Proxies calendar/SLA/capabilities requests from the UI to the
 * core-engine-capabilities service running on a separate port.
 *
 * This allows the UI to call /api/business-calendars/*, /api/sla/*,
 * /api/process-calendars/* through the core-engine (port 8080) without
 * knowing about the capabilities service (port 8082).
 */
@RestController
public class CapabilitiesProxyController {

    private static final Logger log = LoggerFactory.getLogger(CapabilitiesProxyController.class);

    @Value("${luke.capabilities.base-url:http://localhost:8082}")
    private String capabilitiesBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @RequestMapping(
        value = {
            "/api/business-calendars/**",
            "/api/sla/**",
            "/api/process-calendars/**",
            "/api/capabilities/**",
            "/api/my-subscriptions/**",
            "/api/my-capabilities",
            "/api/form-definitions/**",
            "/api/form-instances/**",
            "/api/public/**"
        },
        method = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH }
    )
    public ResponseEntity<String> proxy(HttpServletRequest request, @RequestBody(required = false) String body) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String targetUrl = capabilitiesBaseUrl + path + (query != null ? "?" + query : "");

        // Forward headers. Drop Accept-Encoding so the capabilities service returns
        // plain (uncompressed) JSON: this proxy reads the body as a String and does
        // not decompress, so a gzip response would otherwise be relayed as garbage.
        // Content-Length is recomputed by the client for the new request.
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if ("host".equalsIgnoreCase(name)
                    || "accept-encoding".equalsIgnoreCase(name)
                    || "content-length".equalsIgnoreCase(name)) continue;
            headers.set(name, request.getHeader(name));
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, method, entity, String.class);
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return new ResponseEntity<>(e.getResponseBodyAsString(), e.getStatusCode());
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            return new ResponseEntity<>(e.getResponseBodyAsString(), e.getStatusCode());
        } catch (Exception e) {
            log.warn("Capabilities service unavailable: {}", e.getMessage());
            return ResponseEntity.status(503).body("{\"error\":\"Capabilities service unavailable\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }
}
