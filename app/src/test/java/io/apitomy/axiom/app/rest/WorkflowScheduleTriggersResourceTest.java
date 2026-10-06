package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.app.TestFixtures;
import io.apitomy.axiom.core.entities.WorkflowScheduleTriggerEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * REST tests for the workflow definition schedule trigger endpoints.
 */
@QuarkusTest
class WorkflowScheduleTriggersResourceTest {

    private static final String BASE_PATH = "/api/v1/workflow/definitions";

    /** Creating a trigger returns the trigger with the next run time computed, and it appears in the list. */
    @Test
    void createAndListTrigger() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-create-" + UUID.randomUUID());

        int triggerId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "0 2 * * *", "subjectName": "Nightly", "inputs": {"priority": "high"}}
                    """)
                .when()
                    .post(triggersPath(definitionId))
                .then()
                    .statusCode(200)
                    .body("id", notNullValue())
                    .body("definitionId", equalTo((int) definitionId))
                    .body("cron", equalTo("0 2 * * *"))
                    .body("subjectName", equalTo("Nightly"))
                    .body("inputs.priority", equalTo("high"))
                    .body("enabled", equalTo(true))
                    .body("nextRunAt", notNullValue())
                    .body("lastRunAt", nullValue())
                    .body("createdOn", notNullValue())
                    .extract().path("id");

        given()
                .when()
                    .get(triggersPath(definitionId))
                .then()
                    .statusCode(200)
                    .body("id", hasItem(triggerId));
    }

    /** An invalid cron expression is rejected. */
    @Test
    void invalidCronRejected() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-badcron-" + UUID.randomUUID());
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "not a cron", "subjectName": "Nightly"}
                    """)
                .when()
                    .post(triggersPath(definitionId))
                .then()
                    .statusCode(400);
    }

    /** A blank subject name is rejected. */
    @Test
    void blankSubjectNameRejected() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-blank-" + UUID.randomUUID());
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "0 2 * * *", "subjectName": "  "}
                    """)
                .when()
                    .post(triggersPath(definitionId))
                .then()
                    .statusCode(400);
    }

    /** Creating a trigger for a missing definition returns 404. */
    @Test
    void unknownDefinitionReturns404() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "0 2 * * *", "subjectName": "Nightly"}
                    """)
                .when()
                    .post(triggersPath(999_999_999L))
                .then()
                    .statusCode(404);
    }

    /** Updating changes the cron and recomputes the next run; disabling clears it. */
    @Test
    void updateRecomputesNextRun() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-update-" + UUID.randomUUID());
        int triggerId = createTrigger(definitionId);
        String before = given().when().get(triggersPath(definitionId)).then().extract().path("[0].nextRunAt");

        String after = given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "30 4 * * 1", "subjectName": "Weekly"}
                    """)
                .when()
                    .put(triggersPath(definitionId) + "/" + triggerId)
                .then()
                    .statusCode(200)
                    .body("cron", equalTo("30 4 * * 1"))
                    .body("subjectName", equalTo("Weekly"))
                    .body("nextRunAt", notNullValue())
                    .extract().path("nextRunAt");
        assertNotEquals(before, after);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "30 4 * * 1", "subjectName": "Weekly", "enabled": false}
                    """)
                .when()
                    .put(triggersPath(definitionId) + "/" + triggerId)
                .then()
                    .statusCode(200)
                    .body("enabled", equalTo(false))
                    .body("nextRunAt", nullValue());
    }

    /** A trigger cannot be addressed through a different definition. */
    @Test
    void triggerOfOtherDefinitionReturns404() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-own-" + UUID.randomUUID());
        long otherId = TestFixtures.createWaitDefinition("rest-sched-other-" + UUID.randomUUID());
        int triggerId = createTrigger(definitionId);

        given().when().delete(triggersPath(otherId) + "/" + triggerId).then().statusCode(404);
    }

    /** Deleting a trigger returns 204 and removes it. */
    @Test
    void deleteTrigger() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-delete-" + UUID.randomUUID());
        int triggerId = createTrigger(definitionId);

        given().when().delete(triggersPath(definitionId) + "/" + triggerId).then().statusCode(204);
        given().when().get(triggersPath(definitionId)).then().statusCode(200).body("size()", equalTo(0));
        given().when().delete(triggersPath(definitionId) + "/" + triggerId).then().statusCode(404);
    }

    /** Deleting the workflow definition deletes its triggers. */
    @Test
    void deletingDefinitionDeletesTriggers() {
        long definitionId = TestFixtures.createWaitDefinition("rest-sched-cascade-" + UUID.randomUUID());
        int triggerId = createTrigger(definitionId);

        given().when().delete(BASE_PATH + "/" + definitionId).then().statusCode(204);

        long remaining = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowScheduleTriggerEntity.count("id", (long) triggerId));
        assertEquals(0, remaining);
    }

    private static int createTrigger(long definitionId) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                    {"cron": "0 2 * * *", "subjectName": "Nightly"}
                    """)
                .when()
                    .post(triggersPath(definitionId))
                .then()
                    .statusCode(200)
                    .extract().path("id");
    }

    private static String triggersPath(long definitionId) {
        return BASE_PATH + "/" + definitionId + "/triggers/schedules";
    }
}
