package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.entities.DashboardEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Dashboard validation on create/update, tolerant reads of malformed stored tabs, and dashboards in
 * configuration packs.
 */
@QuarkusTest
class DashboardValidationResourceTest {

    private static final String DASHBOARDS = "/api/v1/dashboards";

    private static final String VALID_TABS = """
            [{"id": "t1", "name": "Main", "widgets": [
                {"id": "w1", "type": "active-subjects", "config": {"maxRows": 5},
                 "layout": {"x": 0, "y": 0, "w": 6, "h": 4}}]}]""";

    private static String body(String name, String tabs) {
        return "{\"name\": " + (name == null ? "null" : "\"" + name + "\"")
                + ", \"labels\": [\"team:a\"], \"isDefault\": false, \"tabs\": " + tabs + "}";
    }

    /** Blank names are rejected on create and update. */
    @Test
    void blankNameIsRejected() {
        given().contentType(ContentType.JSON).body(body("  ", "[]"))
                .when().post(DASHBOARDS)
                .then().statusCode(400).body(containsString("name must not be blank"));

        long id = create("dash-" + UUID.randomUUID());
        given().contentType(ContentType.JSON).body(body("", "[]"))
                .when().put(DASHBOARDS + "/" + id)
                .then().statusCode(400);
    }

    /** Structurally invalid tabs and widgets are rejected with a message naming the problem. */
    @Test
    void invalidTabsAreRejected() {
        String name = "dash-" + UUID.randomUUID();
        given().contentType(ContentType.JSON).body(body(name, "[{\"id\": \"\", \"name\": \"x\", \"widgets\": []}]"))
                .when().post(DASHBOARDS)
                .then().statusCode(400).body(containsString("tabs[0].id"));
        given().contentType(ContentType.JSON).body(body(name, """
                [{"id": "t1", "name": "x", "widgets": [{"id": "w1", "type": "", "config": {},
                  "layout": {"x": 0, "y": 0, "w": 0, "h": 1}}]}]"""))
                .when().post(DASHBOARDS)
                .then().statusCode(400)
                .body(containsString("tabs[0].widgets[0].type"))
                .body(containsString("layout.w"));
        given().contentType(ContentType.JSON).body(body(name, """
                [{"id": "t1", "name": "x", "widgets": [{"id": "w1", "type": "a", "config": {}}]}]"""))
                .when().post(DASHBOARDS)
                .then().statusCode(400).body(containsString("layout is required"));
        given().contentType(ContentType.JSON).body(body(name, """
                [{"id": "t1", "name": "x", "widgets": []}, {"id": "t1", "name": "y", "widgets": []}]"""))
                .when().post(DASHBOARDS)
                .then().statusCode(400).body(containsString("duplicated"));
        given().contentType(ContentType.JSON).body(body(name, VALID_TABS))
                .when().post(DASHBOARDS)
                .then().statusCode(200).body("tabs[0].widgets[0].type", equalTo("active-subjects"));
    }

    /** Malformed stored tabs JSON is returned as empty tabs instead of failing the request. */
    @Test
    void malformedStoredTabsReadAsEmpty() {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            DashboardEntity e = new DashboardEntity();
            e.name = "broken-" + UUID.randomUUID();
            e.tabs = "{not json";
            e.createdOn = Instant.now();
            e.updatedOn = Instant.now();
            e.persist();
            return e.id;
        });
        given().when().get(DASHBOARDS + "/" + id)
                .then().statusCode(200).body("tabs", hasSize(0));
        given().when().get(DASHBOARDS)
                .then().statusCode(200).body("id", hasItem((int) id));
    }

    /** A dashboard exported in a pack can be imported (by name) after the original is deleted. */
    @Test
    void packExportImportRoundTrip() {
        String name = "pack-dash-" + UUID.randomUUID();
        long id = create(name);
        String pack = given().contentType(ContentType.JSON)
                .body("{\"name\": \"p\", \"dashboardIds\": [" + id + "]}")
                .when().post("/api/v1/system/packs/export")
                .then().statusCode(200)
                .body("dashboards", hasSize(1))
                .body("dashboards[0].name", equalTo(name))
                .body("dashboards[0].tabs[0].widgets[0].layout.w", equalTo(6))
                .extract().asString();

        // Importing while the dashboard exists is a name conflict.
        given().contentType(ContentType.JSON).body(pack)
                .when().post("/api/v1/system/packs/import")
                .then().statusCode(409).body("conflicts[0].type", equalTo("dashboard"));

        given().when().delete(DASHBOARDS + "/" + id).then().statusCode(204);
        given().contentType(ContentType.JSON).body(pack)
                .when().post("/api/v1/system/packs/import")
                .then().statusCode(200).body("dashboards", equalTo(1));

        given().when().get(DASHBOARDS)
                .then().statusCode(200)
                .body("findAll { it.name == '" + name + "' }", hasSize(1))
                .body("find { it.name == '" + name + "' }.labels", hasItem("team:a"))
                .body("find { it.name == '" + name + "' }.tabs[0].widgets[0].config.maxRows", equalTo(5));
    }

    /** Pack dashboards are validated like the REST API. */
    @Test
    void invalidPackDashboardIsRejected() {
        String name = "bad-pack-dash-" + UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body("{\"dashboards\": [{\"name\": \"" + name + "\", \"tabs\": [{\"id\": \"t\", \"widgets\": []}]}]}")
                .when().post("/api/v1/system/packs/import")
                .then().statusCode(400)
                .body("problems.'dashboard/" + name + "'[0].message", containsString("tabs[0].name"));
    }

    private static long create(String name) {
        return given().contentType(ContentType.JSON).body(body(name, VALID_TABS))
                .when().post(DASHBOARDS)
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }
}
