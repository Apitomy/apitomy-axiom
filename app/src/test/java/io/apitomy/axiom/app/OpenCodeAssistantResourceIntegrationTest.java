package io.apitomy.axiom.app;

import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.apitomy.axiom.app.assistant.runtime.SessionCompatibilityException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class OpenCodeAssistantResourceIntegrationTest {

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    @Test
    void createSessionReturnsCompatibilityErrorWhenProbeFails() throws IOException {
        when(interactiveSessionDriverFactory.createDriver(any()))
                .thenThrow(new SessionCompatibilityException(
                        SessionCompatibilityException.RUNTIME_UNHEALTHY,
                        "OpenCode runtime health check failed"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"templateId":"general-assistant","name":"opencode-test"}
                        """)
                .when()
                .post("/api/v1/assistant/sessions")
                .then()
                .statusCode(422)
                .body("code", equalTo(SessionCompatibilityException.RUNTIME_UNHEALTHY))
                .body("message", equalTo("OpenCode runtime health check failed"));
    }
}
