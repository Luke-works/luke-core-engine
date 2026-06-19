package com.luke.engine.config;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Factory for {@link RestTemplate}s with explicit connect/read timeouts (#30). A bare
 * {@code new RestTemplate()} has NO timeouts, so a slow/hung upstream (Postmark, etc.)
 * ties up the calling request thread indefinitely → cascading failure. Every
 * server-to-server client should build through here.
 */
public final class RestTemplates {

    private RestTemplates() {}

    public static RestTemplate withTimeouts(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connect.toMillis());
        factory.setReadTimeout((int) read.toMillis());
        return new RestTemplate(factory);
    }
}
