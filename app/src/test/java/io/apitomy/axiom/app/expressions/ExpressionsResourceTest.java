package io.apitomy.axiom.app.expressions;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * REST test for POST /expressions/evaluate.
 */
@QuarkusTest
class ExpressionsResourceTest {

    @Test
    void evaluatesAndReportsErrors() {
        given().contentType(ContentType.JSON)
                .body(Map.of("expression", "context.n + 1", "scope", "action-input", "context", Map.of("n", 41)))
                .post("/api/v1/expressions/evaluate").then().statusCode(200)
                .body("ok", equalTo(true)).body("valueJson", equalTo("42")).body("valueType", equalTo("number"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expression", "event.ref", "scope", "receive-event-match"))
                .post("/api/v1/expressions/evaluate").then().statusCode(200).body("ok", equalTo(false));
        given().contentType(ContentType.JSON).body(Map.of("expression", "1", "scope", "bogus"))
                .post("/api/v1/expressions/evaluate").then().statusCode(400);
    }
}
