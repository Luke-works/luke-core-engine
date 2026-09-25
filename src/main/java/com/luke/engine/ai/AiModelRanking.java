package com.luke.engine.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Which models to put in front of someone, asked of the fleet that has the evidence.
 *
 * <p>A provider lists every model an account can reach and most of them cannot build a form, so
 * the list needs an opinion attached. The fleet forms that opinion from turns people already
 * ran — it records, per turn, which model was used and whether the answer parsed — which is why
 * there is no nightly benchmark here: under bring-your-own-key those turns would run on the
 * workspace's own account, and spending their money on work they did not ask for is the thing
 * this architecture is built to avoid.
 *
 * <p><b>Never load-bearing.</b> A ranking that cannot be fetched means the picker shows the same
 * list it always showed, unsorted and unmarked. So every failure path here returns "no opinion"
 * rather than propagating: a fleet blip must not stop someone choosing a model, and it certainly
 * must not fail the page that lists them.
 */
@Component
public class AiModelRanking {

    private static final Logger log = LoggerFactory.getLogger(AiModelRanking.class);

    /**
     * Short, because it is cheap to be wrong here and expensive to be slow.
     *
     * <p>Every model-list render would otherwise cost a round trip to the fleet. Recommendations
     * move on the scale of days — they are computed over a fortnight of turns — so a few minutes
     * of staleness costs nothing, while the round trip sits directly in front of a dropdown
     * someone is waiting on.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** A ranking call must never cost what a turn costs; nobody is waiting on a good answer. */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final AiProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)  // a redirect must never carry the key elsewhere
            .build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AiModelRanking(AiProperties props) {
        this.props = props;
    }

    private record Cached(List<String> models, Instant at) {
        boolean fresh() {
            return at.plus(TTL).isAfter(Instant.now());
        }
    }

    /**
     * The models worth recommending for this agent, best first — or empty for "no opinion".
     *
     * @param offered what this workspace's key can actually reach. Recommending a model they
     *                cannot run is worse than recommending nothing, so the fleet is told what is
     *                available rather than guessing from a catalogue.
     */
    public List<String> recommended(String agent, String provider, List<String> offered) {
        if (!props.enabled() || offered == null || offered.isEmpty()) return List.of();

        String key = agent + "|" + provider + "|" + offered.hashCode();
        Cached hit = cache.get(key);
        if (hit != null && hit.fresh()) return hit.models();

        try {
            String body = json.writeValueAsString(
                    Map.of("agent", agent, "provider", provider, "offered", offered));
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(props.base() + "/model-ranking"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (props.serviceKey() != null) {
                req.header("X-Agents-Key", props.serviceKey());
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.debug("ai: model ranking unavailable ({})", res.statusCode());
                return remember(key, List.of());
            }
            JsonNode node = json.readTree(res.body()).path("recommended");
            if (!node.isArray()) return remember(key, List.of());
            List<String> out = new java.util.ArrayList<>();
            for (JsonNode m : node) {
                String id = m.asText(null);
                // Only what the workspace can actually run. The fleet is told what is offered,
                // but trusting its answer blindly would let a stale reply name a model that has
                // since been withdrawn from this account.
                if (id != null && offered.contains(id)) out.add(id);
            }
            return remember(key, List.copyOf(out));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {  // NOSONAR - an opinion is optional; the list is not
            log.debug("ai: could not fetch model ranking: {}", e.toString());
            // Cache the silence too, briefly. Otherwise an unreachable fleet means every render
            // of every dropdown pays the timeout again.
            return remember(key, List.of());
        }
    }

    private List<String> remember(String key, List<String> models) {
        cache.put(key, new Cached(models, Instant.now()));
        return models;
    }
}
