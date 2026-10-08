package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that capabilities, not template IDs, drive config-assistant behaviour and are exposed over REST.
 */
@QuarkusTest
class SessionCapabilityEndpointTest {

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> given().delete("/api/v1/assistant/sessions/{id}", id));
    }

    private String createSession(String templateId) throws IOException {
        when(interactiveSessionDriverFactory.createDriver(any()))
                .thenAnswer(i -> {
                    InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
                    when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
                    return driver;
                });
        String id = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"" + templateId + "\",\"name\":\"cap-test\"}")
                .post("/api/v1/assistant/sessions")
                .then().statusCode(200).extract().path("id");
        created.add(id);
        return id;
    }

    @Test
    void configAssistantSessionExposesCapabilitiesAndItems() throws IOException {
        String id = createSession("axiom-config-assistant");
        given().get("/api/v1/assistant/sessions/{id}", id)
                .then().statusCode(200)
                .body("capabilities", containsInAnyOrder("config-items", "validation-feedback"));
        given().get("/api/v1/assistant/sessions/{id}/items", id).then().statusCode(200);
    }

    @Test
    void generalSessionHasNoCapabilitiesAndNoItems() throws IOException {
        String id = createSession("general-assistant");
        given().get("/api/v1/assistant/sessions/{id}", id)
                .then().statusCode(200).body("capabilities", empty());
        given().get("/api/v1/assistant/sessions/{id}/items", id).then().statusCode(400);
    }

    @Test
    void templatesExposeCapabilities() {
        given().get("/api/v1/assistant/templates/{id}", "axiom-config-assistant")
                .then().statusCode(200)
                .body("capabilities", containsInAnyOrder("config-items", "validation-feedback"));
    }
}
