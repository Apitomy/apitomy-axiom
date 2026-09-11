package io.apitomy.axiom.app;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class AssistantEngineSelectionIntegrationTest {

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    private final AtomicReference<InteractiveSessionDriverFactory.DriverRequest> lastRequest =
            new AtomicReference<>();

    @BeforeEach
    void setUpDriverMock() throws IOException {
        when(interactiveSessionDriverFactory.createDriver(any()))
                .thenAnswer(invocation -> {
                    InteractiveSessionDriverFactory.DriverRequest request =
                            invocation.getArgument(0, InteractiveSessionDriverFactory.DriverRequest.class);
                    lastRequest.set(request);
                    return new NoopInteractiveSessionDriver();
                });
    }

    @AfterEach
    void resetDefaultEngine() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"defaultEngine":"claude-code"}
                        """)
                .when()
                .put("/api/v1/system/config")
                .then()
                .statusCode(200);
    }

    @Test
    void templateEngineOverrideIsUsedForAssistantSession() {
        String templateId = "engine-override-" + UUID.randomUUID();
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "templateId":"%s",
                              "name":"Engine Override Template",
                              "description":"Template with explicit engine",
                              "systemPrompt":"Use the override engine",
                              "engine":"opencode"
                            }
                            """.formatted(templateId))
                    .when()
                    .post("/api/v1/assistant/templates")
                    .then()
                    .statusCode(200);

            String sessionId = given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"templateId":"%s","name":"override-test"}
                            """.formatted(templateId))
                    .when()
                    .post("/api/v1/assistant/sessions")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("id");

            InteractiveSessionDriverFactory.DriverRequest request = lastRequest.get();
            assertNotNull(request);
            assertEquals("opencode", request.engineType());

            given()
                    .when()
                    .delete("/api/v1/assistant/sessions/{id}", sessionId)
                    .then()
                    .statusCode(204);
        } finally {
            given()
                    .when()
                    .delete("/api/v1/assistant/templates/{id}", templateId)
                    .then()
                    .statusCode(204);
        }
    }

    @Test
    void templateWithoutEngineInheritsGlobalDefaultEngine() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"defaultEngine":"opencode"}
                        """)
                .when()
                .put("/api/v1/system/config")
                .then()
                .statusCode(200);

        String templateId = "engine-inherit-" + UUID.randomUUID();
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "templateId":"%s",
                              "name":"Engine Inherit Template",
                              "description":"Template with inherited engine",
                              "systemPrompt":"Use the default engine"
                            }
                            """.formatted(templateId))
                    .when()
                    .post("/api/v1/assistant/templates")
                    .then()
                    .statusCode(200);

            String sessionId = given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"templateId":"%s","name":"inherit-test"}
                            """.formatted(templateId))
                    .when()
                    .post("/api/v1/assistant/sessions")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("id");

            InteractiveSessionDriverFactory.DriverRequest request = lastRequest.get();
            assertNotNull(request);
            assertEquals("opencode", request.engineType());

            given()
                    .when()
                    .delete("/api/v1/assistant/sessions/{id}", sessionId)
                    .then()
                    .statusCode(204);
        } finally {
            given()
                    .when()
                    .delete("/api/v1/assistant/templates/{id}", templateId)
                    .then()
                    .statusCode(204);
        }
    }

    private static final class NoopInteractiveSessionDriver implements InteractiveSessionDriver {

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void sendUserMessage(String message) {
            // no-op
        }

        @Override
        public void respondToPermission(String permissionId, boolean allow,
                                        com.fasterxml.jackson.databind.JsonNode toolInput) {
            // no-op
        }

        @Override
        public void interrupt() {
            // no-op
        }

        @Override
        public void destroy() {
            // no-op
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public AssistantSession.Status getStatus() {
            return AssistantSession.Status.RUNNING;
        }

        @Override
        public String getErrorMessage() {
            return null;
        }
    }
}
