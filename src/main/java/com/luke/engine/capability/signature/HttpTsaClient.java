package com.luke.engine.capability.signature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default {@link TsaClient}: requests an RFC-3161 timestamp over HTTP from
 * {@code LUKE_SIGN_TSA_URL} (BouncyCastle {@code TimeStampRequest}, SHA-256 imprint, certReq
 * so the TSA cert is embedded in the token). Free TSAs can rate-limit / be down — use a
 * paid/SLA TSA in prod.
 */
@Component
public class HttpTsaClient implements TsaClient {

    private final String tsaUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpTsaClient(@Value("${luke.sign.trust.tsa-url:http://timestamp.digicert.com}") String tsaUrl) {
        this.tsaUrl = tsaUrl;
    }

    @Override
    public TimeStampToken timeStamp(byte[] signatureValue) throws Exception {
        byte[] imprint = MessageDigest.getInstance("SHA-256").digest(signatureValue);

        TimeStampRequestGenerator reqGen = new TimeStampRequestGenerator();
        reqGen.setCertReq(true);
        TimeStampRequest request = reqGen.generate(TSPAlgorithms.SHA256, imprint);

        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(tsaUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/timestamp-query")
                .header("Accept", "application/timestamp-reply")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.getEncoded()))
                .build();

        HttpResponse<byte[]> httpRes = http.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());
        if (httpRes.statusCode() / 100 != 2) {
            throw new IllegalStateException("TSA " + tsaUrl + " returned HTTP " + httpRes.statusCode());
        }

        TimeStampResponse response = new TimeStampResponse(httpRes.body());
        response.validate(request); // verifies nonce + imprint match
        TimeStampToken token = response.getTimeStampToken();
        if (token == null) {
            throw new IllegalStateException("TSA returned no timestamp token (status "
                    + response.getStatusString() + ")");
        }
        return token;
    }
}
