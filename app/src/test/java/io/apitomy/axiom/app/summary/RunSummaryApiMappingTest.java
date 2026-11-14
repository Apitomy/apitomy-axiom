package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.app.WorkflowRunBeanMapper;
import io.apitomy.axiom.api.beans.WorkflowRunAiSummary;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Checks the REST shape of the run summary feature. */
@QuarkusTest
class RunSummaryApiMappingTest {

    @Inject
    WorkflowRunBeanMapper mapper;

    @Test
    void listMappingCarriesOnlyStatusAndHeadline() {
        WorkflowRunSummaryEntity entity = new WorkflowRunSummaryEntity();
        entity.status = WorkflowRunSummaryEntity.READY;
        entity.headline = "Did things";
        entity.body = "- a";
        entity.engine = "claude-code";
        entity.triggerType = WorkflowRunSummaryEntity.AUTO;
        entity.completedOn = Instant.now();
        WorkflowRunAiSummary light = mapper.toAiSummaryBean(entity, false);
        assertEquals("Did things", light.getHeadline());
        assertNull(light.getBody());
        WorkflowRunAiSummary full = mapper.toAiSummaryBean(entity, true);
        assertEquals("- a", full.getBody());
        assertEquals("claude-code", full.getEngine());
    }

    @Test
    void newDefinitionIsNotOptedIn() {
        given().contentType(ContentType.JSON).body("{\"name\":\"sum-default-" + System.nanoTime() + "\"}")
                .when().post("/api/v1/workflow/definitions")
                .then().statusCode(200).body("summarizeRuns", equalTo(false));
    }

    @Test
    void definitionOptInRoundTrips() {
        int id = given().contentType(ContentType.JSON).body("{\"name\":\"sum-on-" + System.nanoTime() + "\"}")
                .when().post("/api/v1/workflow/definitions").then().statusCode(200).extract().path("id");
        given().contentType(ContentType.JSON).body("{\"summarizeRuns\":true}")
                .when().put("/api/v1/workflow/definitions/" + id)
                .then().statusCode(200).body("summarizeRuns", equalTo(true));
        given().contentType(ContentType.JSON).body("{\"description\":\"x\"}")
                .when().put("/api/v1/workflow/definitions/" + id)
                .then().statusCode(200).body("summarizeRuns", equalTo(true));
    }

    @Test
    void systemConfigStoresAndClearsSummarySettings() {
        String engine = given().when().get("/api/v1/system/config").then().extract().path("defaultEngine");
        given().contentType(ContentType.JSON)
                .body("{\"defaultEngine\":\"" + engine + "\",\"summaryEngine\":\"" + engine
                        + "\",\"summaryModel\":\"m1\"}")
                .when().put("/api/v1/system/config")
                .then().statusCode(200).body("summaryModel", equalTo("m1"));
        given().contentType(ContentType.JSON)
                .body("{\"defaultEngine\":\"" + engine + "\",\"summaryEngine\":\"\",\"summaryModel\":\"\"}")
                .when().put("/api/v1/system/config")
                .then().statusCode(200).body("summaryEngine", org.hamcrest.Matchers.nullValue());
    }
}
