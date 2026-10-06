package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SdkResourceTest {

    private static final String SDK_PATH = "/api/v1/sdk/functions";

    @Inject
    SdkCallService sdkCallService;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Test
    void testListAllFunctions() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(15))
                .body("name", hasItems(
                    "axiom_add_subject_label",
                    "axiom_fire_event",
                    "axiom_list_subjects",
                    "axiom_get_subject",
                    "axiom_list_agents",
                    "axiom_get_workflow_run",
                    "axiom_list_subject_runs"))
                .body("[0].name", notNullValue())
                .body("[0].description", notNullValue())
                .body("[0].parameters", notNullValue());
    }

    @Test
    void testListSdkCallOnlyFunctions() {
        given()
            .queryParam("sdkCallOnly", true)
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("size()", greaterThan(0))
                .body("sdkCallSupported", everyItem(equalTo(true)))
                .body("name", hasItems(
                    "axiom_add_subject_label",
                    "axiom_fire_event",
                    "axiom_create_subject"));
    }

    @Test
    void testSdkCallOnlyExcludesNonSdkCallFunctions() {
        given()
            .queryParam("sdkCallOnly", true)
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems(
                    "axiom_list_tools",
                    "axiom_get_activity_log")));
    }

    @Test
    void testFunctionHasTypedParameters() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("find { it.name == 'axiom_get_subject' }.parameters.size()",
                    equalTo(1))
                .body("find { it.name == 'axiom_get_subject' }.parameters[0].name",
                    equalTo("subjectId"))
                .body("find { it.name == 'axiom_get_subject' }.parameters[0].type",
                    equalTo("number"))
                .body("find { it.name == 'axiom_get_subject' }.parameters[0].required",
                    equalTo(true));
    }

    /**
     * Reports were removed: their SDK functions are no longer listed, and calling one is
     * handled like any other unknown function. The SDK resource only lists functions, so the
     * call is made through {@link SdkCallService}, which backs workflow {@code sdk:} calls.
     */
    @Test
    void removedReportFunctionsAreUnknown() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems("axiom_add_report_label")))
                .body("name", not(hasItems("axiom_remove_report_label")))
                .body("name", not(hasItems("axiom_list_report_definitions")))
                .body("name", not(hasItems("axiom_list_reports")));

        SdkCallService.SdkCallResult result = sdkCallService.execute("axiom_list_reports", Map.of());
        assertFalse(result.success());
        assertTrue(result.output().startsWith("Unknown SDK function"), result.output());
    }

    /**
     * Action types run only from workflow action nodes, so the SDK no longer offers task creation.
     */
    @Test
    void createTaskFunctionIsUnknown() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems("axiom_create_task")));

        SdkCallService.SdkCallResult result = sdkCallService.execute("axiom_create_task", Map.of());
        assertFalse(result.success());
        assertTrue(result.output().startsWith("Unknown SDK function"), result.output());
    }

    /**
     * The subject lifecycle was removed: closing and reopening subjects are no longer SDK functions.
     */
    @Test
    void closeSubjectFunctionIsUnknown() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems("axiom_close_subject", "axiom_reopen_subject")));

        for (String name : new String[] {"axiom_close_subject", "axiom_reopen_subject"}) {
            SdkCallService.SdkCallResult result = sdkCallService.execute(name, Map.of("subjectId", 1));
            assertFalse(result.success());
            assertTrue(result.output().startsWith("Unknown SDK function"), result.output());
        }
    }

    /**
     * axiom_get_workflow_run returns the run's title and labels.
     */
    @Test
    void getWorkflowRunReturnsRunMetadata() {
        long subjectId = TestFixtures.createSubject("sdk-get-run");
        long definitionId = TestFixtures.createWaitDefinition("SDK Get Run");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId,
                        new RunRequest("SDK run", Map.of("team", "core"), null, null), null));

        SdkCallService.SdkCallResult result = QuarkusTransaction.requiringNew().call(() ->
                sdkCallService.execute("axiom_get_workflow_run", Map.of("runId", run.id)));

        assertTrue(result.success(), result.output());
        assertEquals("SDK run", result.outputMap().get("title"));
        assertEquals(Map.of("team", "core"), result.outputMap().get("labels"));
        assertEquals(subjectId, result.outputMap().get("subjectId"));
        assertEquals("waiting", result.outputMap().get("status"));

        SdkCallService.SdkCallResult missing = QuarkusTransaction.requiringNew().call(() ->
                sdkCallService.execute("axiom_get_workflow_run", Map.of("runId", 99999999L)));
        assertFalse(missing.success());
        assertTrue(missing.output().contains("not found"), missing.output());
    }

    /**
     * axiom_list_subject_runs lists a subject's runs, newest first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listSubjectRunsReturnsRunsNewestFirst() {
        long subjectId = TestFixtures.createSubject("sdk-list-runs");
        long defA = TestFixtures.createWaitDefinition("SDK List A");
        long defB = TestFixtures.createWaitDefinition("SDK List B");
        WorkflowRunEntity first = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defA));
        WorkflowRunEntity second = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defB));

        SdkCallService.SdkCallResult result = QuarkusTransaction.requiringNew().call(() ->
                sdkCallService.execute("axiom_list_subject_runs", Map.of("subjectId", subjectId)));

        assertTrue(result.success(), result.output());
        List<Map<String, Object>> runs = (List<Map<String, Object>>) result.outputMap().get("runs");
        assertEquals(2, runs.size());
        assertEquals(second.id, runs.get(0).get("id"));
        assertEquals(first.id, runs.get(1).get("id"));
    }
}
