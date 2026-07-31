package com.luke.engine.capability.email;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public webhook Postmark POSTs inbound mail to. Unauthenticated by path
 * ({@code /api/public/**}); the security boundary is the unguessable per-tenant {@code token}
 * in the URL, which resolves to exactly one tenant's server.
 *
 * <p>The work lives in {@link InboundEmailIntakeService} — store, dedup, route, start. This
 * class is only the edge: resolve the token, hand over the payload, answer 2xx.
 *
 * <p><b>Always 2xx once the token is valid.</b> Postmark retries anything else, and every retry
 * of a message we already stored is a chance to create a second task for the same mail. An
 * unmatched recipient is still stored for the tenant's inbox and still reported as accepted.
 */
@RestController
@RequestMapping("/api/public/email")
public class PublicInboundEmailController {

    private final InboundEmailIntakeService intake;

    public PublicInboundEmailController(InboundEmailIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping("/inbound/{token}")
    public Map<String, Object> inbound(@PathVariable String token, @RequestBody JsonNode payload) {
        EmailServer server = intake.serverForToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown inbound token"));

        InboundEmailIntakeService.IntakeResult result = intake.intake(server, payload);

        // LinkedHashMap, not Map.of: several values are legitimately null (no rule matched) and
        // Map.of rejects nulls with an NPE — which Postmark would see as a 500 and retry.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("received", result.received());
        body.put("matched", result.matched());
        body.put("duplicate", result.duplicate());
        body.put("messageId", result.messageId());
        body.put("taskCreated", result.taskCreated());
        body.put("workflows", result.workflows());
        body.put("rule", result.matchedRule());
        return body;
    }
}
