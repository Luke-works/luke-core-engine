package com.luke.engine.capability.signature;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds the {@link S3Client} for {@link S3DocumentStore}, only when {@code luke.docstore.provider=s3}.
 * Mirrors luke-file-proxy's S3Config so core writes signature bytes to the SAME bucket the proxy reads
 * (DOC-7). The bean is named {@code documentStoreS3Client} to avoid clashing with any other S3 client.
 *
 * <p>Credentials: explicit {@code LUKE_DOCSTORE_ACCESS_KEY/SECRET_KEY} when set, else the default AWS
 * chain. An optional {@code LUKE_DOCSTORE_ENDPOINT} (path-style) targets S3-compatible stores (R2/B2/MinIO).
 */
@Configuration
@ConditionalOnProperty(name = "luke.docstore.provider", havingValue = "s3")
public class DocumentStoreS3Config {

    @Bean
    public S3Client documentStoreS3Client(
            @Value("${luke.docstore.region:}") String region,
            @Value("${luke.docstore.endpoint:}") String endpoint,
            @Value("${luke.docstore.access-key:}") String accessKey,
            @Value("${luke.docstore.secret-key:}") String secretKey) {

        if (!StringUtils.hasText(region)) {
            throw new IllegalStateException("luke.docstore.region must be set when provider=s3");
        }
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }
}
