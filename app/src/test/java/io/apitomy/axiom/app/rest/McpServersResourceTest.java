package io.apitomy.axiom.app.rest;

import io.restassured.http.ContentType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * REST tests for the MCP servers API, focusing on CRUD and validation.
 */
@QuarkusTest
class McpServersResourceTest {

    private static final String BASE = "/api/v1/mcp-servers";

    @AfterEach
    void cleanUp() {
        List<Integer> ids = given().get(BASE).then().statusCode(200).extract().jsonPath().getList("id");
        ids.forEach(id -> given().delete(BASE + "/{id}", id));
    }

    private static String stdio(String name) {
        return "{\"name\":\"" + name + "\",\"serverCommand\":\"npx\",\"serverArgs\":[\"-y\",\"pkg\"]}";
    }

    private static int create(String body) {
        return given().contentType(ContentType.JSON).body(body).post(BASE)
                .then().statusCode(200).extract().path("id");
    }

    @Test
    void createsAndReadsStdioServer() {
        int id = create(stdio("github"));
        given().get(BASE + "/{id}", id).then().statusCode(200)
                .body("name", equalTo("github"))
                .body("serverCommand", equalTo("npx"))
                .body("serverArgs[1]", equalTo("pkg"));
    }

    @Test
    void createsHttpServer() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"remote\",\"serverUrl\":\"https://example.com/mcp\"}")
                .post(BASE).then().statusCode(200).body("id", notNullValue());
    }

    @Test
    void listIsSortedByName() {
        create(stdio("zeta"));
        create(stdio("alpha"));
        given().get(BASE).then().statusCode(200)
                .body("[0].name", equalTo("alpha"))
                .body("[1].name", equalTo("zeta"));
    }

    @Test
    void duplicateNameOnCreateReturns409() {
        create(stdio("dup"));
        given().contentType(ContentType.JSON).body(stdio("dup")).post(BASE)
                .then().statusCode(409).body("message", containsString("dup"));
    }

    @Test
    void renameToExistingNameReturns409() {
        create(stdio("first"));
        int second = create(stdio("second"));
        given().contentType(ContentType.JSON).body(stdio("first")).put(BASE + "/{id}", second)
                .then().statusCode(409);
    }

    @Test
    void updateKeepingSameNameSucceeds() {
        int id = create(stdio("same"));
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"same\",\"description\":\"d\",\"serverCommand\":\"node\"}")
                .put(BASE + "/{id}", id).then().statusCode(200).body("description", equalTo("d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "a__b", "@axiom-tools", "slash/name", "_lead", "trail-",
        "this-name-is-way-too-long-for-an-mcp-server-name-and-should-be-rejected-x"})
    void invalidNameReturns400(String name) {
        given().contentType(ContentType.JSON).body(stdio(name)).post(BASE)
                .then().statusCode(400).body("message", containsString("name"));
    }

    @Test
    void missingNameReturns400() {
        given().contentType(ContentType.JSON).body("{\"serverCommand\":\"npx\"}").post(BASE)
                .then().statusCode(400);
    }

    @Test
    void bothCommandAndUrlReturns400() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"both\",\"serverCommand\":\"npx\",\"serverUrl\":\"https://x.io/mcp\"}")
                .post(BASE).then().statusCode(400).body("message", containsString("either"));
    }

    @Test
    void neitherCommandNorUrlReturns400() {
        given().contentType(ContentType.JSON).body("{\"name\":\"none\"}").post(BASE)
                .then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://example.com/mcp", "/relative/path"})
    void invalidUrlReturns400(String url) {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"bad-url\",\"serverUrl\":\"" + url + "\"}")
                .post(BASE).then().statusCode(400).body("message", containsString("URL"));
    }

    @Test
    void validationAppliesOnUpdate() {
        int id = create(stdio("valid"));
        given().contentType(ContentType.JSON).body("{\"name\":\"valid\"}").put(BASE + "/{id}", id)
                .then().statusCode(400);
    }

    @Test
    void unknownIdReturns404() {
        given().get(BASE + "/{id}", 999999).then().statusCode(404);
        given().delete(BASE + "/{id}", 999999).then().statusCode(404);
    }
}
