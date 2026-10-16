package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

/**
 * Outside dev mode, the API does not allow cross-origin browser requests: a web page from another site must not be
 * able to call Axiom (the UI is served from the same origin).
 */
@QuarkusTest
class CrossOriginTest {

    @Test
    void aForeignOriginIsNotAllowed() {
        given().header("Origin", "https://evil.example")
                .when().get("/api/v1/system/health")
                .then().header("Access-Control-Allow-Origin", nullValue());
        given().header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/api/v1/secrets")
                .then().header("Access-Control-Allow-Origin", nullValue());
    }
}
