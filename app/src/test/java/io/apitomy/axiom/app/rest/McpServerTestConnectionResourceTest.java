package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.entities.McpServerEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * REST tests for testing MCP server connections and storing discovered tools.
 */
@QuarkusTest
class McpServerTestConnectionResourceTest {

    private static final String BASE = "/api/v1/mcp-servers";

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> McpServerEntity.delete("name like ?1", "mtc-%"));
    }

    private static String fakeServer() throws Exception {
        return Path.of(McpServerTestConnectionResourceTest.class.getResource("/mcp/fake-stdio-server.js").toURI())
                .toString().replace("\\", "\\\\");
    }

    @Test
    void testingASavedServerStoresItsTools() throws Exception {
        int id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"mtc-fake\",\"serverCommand\":\"node\",\"serverArgs\":[\"" + fakeServer() + "\"]}")
                .post(BASE).then().statusCode(200).extract().path("id");

        given().post(BASE + "/{id}/test", id).then().statusCode(200)
                .body("success", equalTo(true))
                .body("serverName", equalTo("fake-stdio"))
                .body("tools.name", contains("echo", "add"));

        given().get(BASE + "/{id}", id).then().statusCode(200)
                .body("tools.name", contains("echo", "add"))
                .body("toolsDiscoveredOn", notNullValue());
    }

    @Test
    void testingAnUnsavedDefinition() throws Exception {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"mtc-draft\",\"serverCommand\":\"node\",\"serverArgs\":[\"" + fakeServer() + "\"],"
                        + "\"serverEnv\":{\"FAKE_TOKEN\":\"plain\"}}")
                .post(BASE + "/test").then().statusCode(200)
                .body("success", equalTo(true))
                .body("tools[0].description", equalTo("Echo plain"));
    }

    @Test
    void missingSecretIsAFailedTestNotAnError() throws Exception {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"mtc-secret\",\"serverCommand\":\"node\",\"serverArgs\":[\"" + fakeServer() + "\"],"
                        + "\"serverEnv\":{\"FAKE_TOKEN\":\"${secret:MTC_NOPE}\"}}")
                .post(BASE + "/test").then().statusCode(200)
                .body("success", equalTo(false))
                .body("error", containsString("MTC_NOPE"));
    }

    @Test
    void invalidDefinitionIsRejected() {
        given().contentType(ContentType.JSON).body("{\"name\":\"mtc-bad\"}")
                .post(BASE + "/test").then().statusCode(400);
    }
}
