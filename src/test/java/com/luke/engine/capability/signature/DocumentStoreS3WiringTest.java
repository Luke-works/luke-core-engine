package com.luke.engine.capability.signature;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * DOC-7 byte-path: boots the FULL engine context under {@code provider=s3} (dummy creds — no network
 * at startup) and asserts the {@link S3DocumentStore} is the selected {@link DocumentStore}. This is
 * the guard for the prod wiring the default ({@code provider=local}) tests never exercise — so a future
 * conditional/bean mistake fails in CI, not on the Render deploy.
 */
@SpringBootTest(properties = {
        "luke.docstore.provider=s3",
        "luke.docstore.region=us-east-2",
        "luke.docstore.bucket=luke-docstore-test",
        "luke.docstore.access-key=test",
        "luke.docstore.secret-key=test"
})
class DocumentStoreS3WiringTest {

    @Autowired
    DocumentStore documentStore;

    @Test
    void s3DocumentStoreIsSelectedAndContextBoots() {
        assertThat(documentStore).isInstanceOf(S3DocumentStore.class);
    }
}
