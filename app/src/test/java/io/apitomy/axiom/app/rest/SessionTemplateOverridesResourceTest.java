package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST tests for the built-in session template overrides sub-resource.
 */
@QuarkusTest
class SessionTemplateOverridesResourceTest {

    private static final String BASE = "/api/v1/assistant/templates";
    private static final String BUILT_IN = "general-assistant";

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    @AfterEach
    void cleanUp() {
        given().delete(BASE + "/{id}/overrides", BUILT_IN);
    }

    @Test
    void getReturnsEmptyOverridesByDefault() {
        given().get(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200)
                .body("model", nullValue())
                .body("engine", nullValue())
                .body("environment", nullValue());
        given().get(BASE + "/{id}", BUILT_IN)
                .then().statusCode(200).body("overriddenFields", empty());
    }

    @Test
    void putAppliesOverridesAndReportsOverriddenFields() {
        given().contentType(ContentType.JSON)
                .body("{\"model\":\"m1\",\"environment\":{\"FOO\":\"bar\"}}")
                .put(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200)
                .body("model", equalTo("m1"))
                .body("environment.FOO", equalTo("bar"))
                .body("builtIn", equalTo(true))
                .body("overriddenFields", containsInAnyOrder("model", "environment"));

        given().get(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200).body("model", equalTo("m1"));
        given().get(BASE)
                .then().statusCode(200)
                .body("find { it.templateId == 'general-assistant' }.overriddenFields", hasItem("model"));
    }

    @Test
    void deleteResetsToDefaults() {
        given().contentType(ContentType.JSON).body("{\"model\":\"m1\"}")
                .put(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(200);
        given().delete(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(204);
        given().get(BASE + "/{id}", BUILT_IN)
                .then().statusCode(200)
                .body("model", nullValue())
                .body("overriddenFields", empty());
    }

    @Test
    void unknownTemplateReturns404() {
        given().get(BASE + "/{id}/overrides", "nope").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{\"model\":\"m\"}")
                .put(BASE + "/{id}/overrides", "nope").then().statusCode(404);
        given().delete(BASE + "/{id}/overrides", "nope").then().statusCode(404);
    }

    @Test
    void userTemplateReturns400() {
        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"u\",\"description\":\"d\",\"systemPrompt\":\"p\"}")
                .post(BASE).then().statusCode(200).extract().path("templateId");
        try {
            given().get(BASE + "/{id}/overrides", id).then().statusCode(400);
            given().contentType(ContentType.JSON).body("{\"model\":\"m\"}")
                    .put(BASE + "/{id}/overrides", id).then().statusCode(400);
            given().delete(BASE + "/{id}/overrides", id).then().statusCode(400);
        } finally {
            given().delete(BASE + "/{id}", id);
        }
    }

    @Test
    void existingBuiltInPutStillForbidden() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"p\"}")
                .put(BASE + "/{id}", BUILT_IN).then().statusCode(403);
    }

    @Test
    void putUnknownEngineReturns400() {
        given().contentType(ContentType.JSON).body("{\"engine\":\"bogus\"}")
                .put(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(400);
        given().get(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200).body("engine", nullValue());
    }

    @Test
    void putWithoutBodyReturns400() {
        given().contentType(ContentType.JSON)
                .put(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(400);
    }

    @Test
    void sessionCreationUsesOverriddenEngineAndModel() throws IOException {
        String defaultEngine = given().get("/api/v1/system/config").then().statusCode(200)
                .extract().path("defaultEngine");
        List<String> interactive = given().get("/api/v1/system/config").then().statusCode(200)
                .extract().path("engines.findAll { it.supportsInteractiveSessions }.type");
        String engine = interactive.stream()
                .filter(type -> !type.equals(defaultEngine))
                .findFirst()
                .orElse(interactive.get(0));
        boolean engineDiffers = !engine.equals(defaultEngine);
        String model = "override-test-model";
        given().contentType(ContentType.JSON)
                .body("{\"engine\":\"" + engine + "\",\"model\":\"" + model + "\"}")
                .put(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(200);

        when(interactiveSessionDriverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
        String sessionId = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"" + BUILT_IN + "\",\"name\":\"override-test\"}")
                .post("/api/v1/assistant/sessions")
                .then().statusCode(200).extract().path("id");
        try {
            ArgumentCaptor<InteractiveSessionDriverFactory.DriverRequest> captor =
                    ArgumentCaptor.forClass(InteractiveSessionDriverFactory.DriverRequest.class);
            verify(interactiveSessionDriverFactory, atLeastOnce()).createDriver(captor.capture());
            InteractiveSessionDriverFactory.DriverRequest request = captor.getValue();
            assertEquals(model, request.model());
            assertEquals(engine, request.engineType());
            Assumptions.assumeTrue(engineDiffers,
                    "Only one interactive engine available; engine override is not distinguishable");
        } finally {
            given().delete("/api/v1/assistant/sessions/{id}", sessionId);
        }
    }
}
