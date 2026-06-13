package com.luke.engine.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

    // JDK HttpClient factory (Java 11+) — supports PATCH; the default
    // SimpleClientHttpRequestFactory uses HttpURLConnection, which rejects it
    // ("Invalid HTTP method: PATCH"). Restricted headers are dropped below.
    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

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
            // Drop hop-by-hop / managed headers that Java's HttpClient forbids
            // setting (host, content-length, connection, transfer-encoding,
            // expect, upgrade) plus accept-encoding (we don't decompress).
            //
            // Also drop the browser's CORS-negotiation headers (origin,
            // access-control-request-*): this is a server-to-server hop, so they
            // are meaningless here — and forwarding Origin makes the capability
            // engine's CORS filter reject the request ("Invalid CORS request").
            // The old RestTemplate (HttpURLConnection) silently dropped Origin;
            // the JDK HttpClient factory does not, so we drop it explicitly.
            if ("host".equalsIgnoreCase(name)
                    || "accept-encoding".equalsIgnoreCase(name)
                    || "content-length".equalsIgnoreCase(name)
                    || "connection".equalsIgnoreCase(name)
                    || "transfer-encoding".equalsIgnoreCase(name)
                    || "expect".equalsIgnoreCase(name)
                    || "upgrade".equalsIgnoreCase(name)
                    || "origin".equalsIgnoreCase(name)
                    || "access-control-request-method".equalsIgnoreCase(name)
                    || "access-control-request-headers".equalsIgnoreCase(name)) continue;
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
