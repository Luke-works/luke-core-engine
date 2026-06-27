package com.luke.engine.capability.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luke.engine.capability.form.FormDefinitionController.CheckInBody;
import com.luke.engine.capability.form.FormDefinitionController.CreateForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The design-time lifecycle gates introduced with per-version sign-off:
 *   - check-in is a SNAPSHOT — it never auto-publishes (the form stays DRAFT),
 *   - publish is BLOCKED until the target version is signed off,
 *   - sign-off stamps the latest version, which then publishes; and signing off with
 *     no versions yet is rejected.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:formlifecycle;DB_CLOSE_DELAY=-1",
        "luke.forms.outbox-enabled=false"
})
class FormLifecycleControllerTest {

    @Autowired FormDefinitionController controller;

    private static final String SCHEMA = "{\"root\":[],\"entities\":{}}";

    @Test
    void checkIn_isASnapshot_andDoesNotAutoPublish() {
        String tenant = "t-lifecycle-1";
        FormDefinition form = controller.create(tenant, "u1", new CreateForm("Contact", null));

        FormVersion v1 = controller.checkIn(tenant, "u1", form.getId(), new CheckInBody(SCHEMA, null));
        assertEquals(1, v1.getVersion());
        // Even with publish=true in the body, check-in must not publish.
        controller.checkIn(tenant, "u1", form.getId(), new CheckInBody(SCHEMA, Boolean.TRUE));

        FormDefinition after = controller.get(tenant, form.getId());
        assertEquals("DRAFT", after.getStatus(), "check-in must leave the form in DRAFT");
        assertNull(after.getPublishedVersion(), "check-in must not publish");
    }

    @Test
    void publish_isBlocked_untilTheVersionIsSignedOff() {
        String tenant = "t-lifecycle-2";
        FormDefinition form = controller.create(tenant, "u1", new CreateForm("Survey", null));
        controller.checkIn(tenant, "u1", form.getId(), new CheckInBody(SCHEMA, null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.publish(tenant, "u1", form.getId(), 1));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());

        // Sign off the latest version, then publish goes live.
        controller.signOff(tenant, "u1", form.getId());
        FormDefinition published = controller.publish(tenant, "u1", form.getId(), 1);
        assertEquals("PUBLISHED", published.getStatus());
        assertEquals(1, published.getPublishedVersion());
    }

    @Test
    void signOff_withNoVersions_isRejected() {
        String tenant = "t-lifecycle-3";
        FormDefinition form = controller.create(tenant, "u1", new CreateForm("Empty", null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.signOff(tenant, "u1", form.getId()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
    }

    @Test
    void signOff_marksTheLatestVersion_soANewerCheckInMustBeReSignedOff() {
        String tenant = "t-lifecycle-4";
        FormDefinition form = controller.create(tenant, "u1", new CreateForm("Intake", null));
        controller.checkIn(tenant, "u1", form.getId(), new CheckInBody(SCHEMA, null));   // v1
        controller.signOff(tenant, "u1", form.getId());                                   // v1 signed off
        controller.checkIn(tenant, "u1", form.getId(), new CheckInBody(SCHEMA, null));   // v2 (unsigned)

        // v1 still publishable (signed off); v2 not yet.
        FormDefinition pub1 = controller.publish(tenant, "u1", form.getId(), 1);
        assertEquals(1, pub1.getPublishedVersion());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.publish(tenant, "u1", form.getId(), 2));
        assertTrue(ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT));
    }
}
