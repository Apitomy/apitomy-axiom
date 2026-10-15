package io.apitomy.axiom.app.expressions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void acceptsTheSubscriptionFilterScope() {
        // Without an event the scope is accepted but cannot evaluate.
        given().contentType(ContentType.JSON)
                .body(Map.of("expression", "event.type == 'pr.created'", "scope", "subscription-filter"))
                .post("/api/v1/expressions/evaluate").then().statusCode(200)
                .body("ok", equalTo(false)).body("error", equalTo("Scope 'subscription-filter' needs an eventId"));
    }

    /** The contract's scope enum lists exactly the service's scopes, in order. */
    @Test
    void contractListsEveryScope() throws Exception {
        JsonNode contract = new ObjectMapper().readTree(
                Files.readAllBytes(Path.of("../common/api/src/main/resources/openapi.json")));
        List<String> listed = new ArrayList<>();
        contract.at("/components/schemas/ExpressionEvaluateRequest/properties/scope/enum")
                .forEach(n -> listed.add(n.asText()));
        List<String> expected = Arrays.stream(ExpressionService.Scope.values())
                .map(ExpressionService.Scope::jsonName).toList();
        assertEquals(expected, listed);
    }
}
