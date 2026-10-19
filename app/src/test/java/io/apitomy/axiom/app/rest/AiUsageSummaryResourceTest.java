package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.app.TestFixtures;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST tests for {@code GET /usage/ai/summary}.
 */
@QuarkusTest
class AiUsageSummaryResourceTest {

    private static final String SUMMARY = "/api/v1/usage/ai/summary";

    /** Grouping by subject sums cost/tokens per subject, orders by cost and excludes other subjects. */
    @Test
    void groupBySubjectScopedByLabels() {
        String label = "ai-sum-" + UUID.randomUUID();
        long cheap = subject("cheap-" + label, label);
        long pricey = subject("pricey-" + label, label);
        long outside = subject("outside-" + label, "other-" + label);
        usage(cheap, null, "e1", "m1", "analyze", 1.0, Instant.now());
        usage(pricey, null, "e1", "m1", "analyze", 2.0, Instant.now());
        usage(pricey, null, "e1", "m1", "analyze", 3.0, Instant.now());
        usage(outside, null, "e1", "m1", "analyze", 50.0, Instant.now());

        JsonPath json = given().queryParam("groupBy", "subject").queryParam("filterLabels", label)
                .when().get(SUMMARY)
                .then().statusCode(200)
                .body("groupBy", equalTo("subject"))
                .body("rows", hasSize(2))
                .extract().jsonPath();
        assertEquals(String.valueOf(pricey), json.getString("rows[0].key"));
        assertEquals("pricey-" + label, json.getString("rows[0].label"));
        assertEquals(5.0, json.getDouble("rows[0].costUsd"), 1e-9);
        assertEquals(2, json.getInt("rows[0].invocations"));
        assertEquals(20, json.getInt("rows[0].inputTokens"));
        assertEquals(6.0, json.getDouble("totalCostUsd"), 1e-9);
        assertNotNull(json.get("excludedUnscopedCostUsd"));
    }

    /** Workflow grouping keys by definition id with its name; usage outside a workflow gets key "". */
    @Test
    void groupByWorkflowIncludesNullKeyRow() {
        String label = "ai-wf-" + UUID.randomUUID();
        long subjectId = subject("s-" + label, label);
        long definitionId = definition("wf-" + label);
        usage(subjectId, definitionId, "e1", "m1", "analyze", 4.0, Instant.now());
        usage(subjectId, null, "e1", "m1", "analyze", 1.5, Instant.now());

        JsonPath json = given().queryParam("groupBy", "workflow").queryParam("filterLabels", label)
                .when().get(SUMMARY)
                .then().statusCode(200).body("rows", hasSize(2))
                .extract().jsonPath();
        assertEquals(String.valueOf(definitionId), json.getString("rows[0].key"));
        assertEquals("wf-" + label, json.getString("rows[0].label"));
        assertEquals("", json.getString("rows[1].key"));
        assertEquals("(none)", json.getString("rows[1].label"));
        assertEquals(1.5, json.getDouble("rows[1].costUsd"), 1e-9);
    }

    /** Engine, model and action type group on the raw value; the since window drops older records. */
    @Test
    void groupByEngineModelActionTypeAndSince() {
        String label = "ai-emt-" + UUID.randomUUID();
        long subjectId = subject("s-" + label, label);
        usage(subjectId, null, "engine-a", "model-a", "act-a", 1.0, Instant.now());
        usage(subjectId, null, "engine-b", "model-b", "act-b", 2.0, Instant.now());
        usage(subjectId, null, "engine-a", "model-a", "act-a", 9.0, Instant.now().minus(Duration.ofDays(3)));

        for (Map.Entry<String, String> e : Map.of("engine", "engine-b", "model", "model-b",
                "actionType", "act-b").entrySet()) {
            given().queryParam("groupBy", e.getKey()).queryParam("filterLabels", label)
                    .queryParam("since", Instant.now().minus(Duration.ofDays(1)).toString())
                    .when().get(SUMMARY)
                    .then().statusCode(200)
                    .body("rows", hasSize(2))
                    .body("rows[0].key", equalTo(e.getValue()))
                    .body("rows[0].label", equalTo(e.getValue()))
                    .body("totalCostUsd", equalTo(3.0f));
        }
        given().queryParam("groupBy", "engine").queryParam("filterLabels", label)
                .when().get(SUMMARY)
                .then().statusCode(200)
                .body("rows[0].key", equalTo("engine-a"))
                .body("rows[0].costUsd", equalTo(10.0f));
    }

    /** With labels, usage without a subject is excluded and its cost reported separately. */
    @Test
    void labelsExcludeUnscopedUsage() {
        String engine = "unscoped-" + UUID.randomUUID();
        usage(null, null, engine, "m", "a", 7.0, Instant.now());

        // Without labels the unscoped record shows up (grouped by its unique engine) and nothing is excluded.
        JsonPath all = given().queryParam("groupBy", "engine").queryParam("limit", 100)
                .when().get(SUMMARY)
                .then().statusCode(200).body("excludedUnscopedCostUsd", nullValue())
                .extract().jsonPath();
        List<String> keys = all.getList("rows.key");
        assertTrue(keys.contains(engine), keys.toString());

        JsonPath scoped = given().queryParam("groupBy", "engine")
                .queryParam("filterLabels", "nobody-has-" + UUID.randomUUID())
                .when().get(SUMMARY)
                .then().statusCode(200).body("rows", hasSize(0))
                .extract().jsonPath();
        assertEquals(0.0, scoped.getDouble("totalCostUsd"), 1e-9);
        assertTrue(scoped.getDouble("excludedUnscopedCostUsd") >= 7.0);
    }

    /** Limit is clamped and invalid groupBy values are rejected. */
    @Test
    void limitAndValidation() {
        String label = "ai-lim-" + UUID.randomUUID();
        long subjectId = subject("s-" + label, label);
        for (int i = 0; i < 3; i++) {
            usage(subjectId, null, "eng-" + i, "m", "a", 1.0 + i, Instant.now());
        }
        given().queryParam("groupBy", "engine").queryParam("filterLabels", label).queryParam("limit", 2)
                .when().get(SUMMARY)
                .then().statusCode(200).body("rows", hasSize(2)).body("rows[0].key", equalTo("eng-2"));
        given().queryParam("groupBy", "bogus").when().get(SUMMARY).then().statusCode(400);
        given().when().get(SUMMARY).then().statusCode(400);
        given().queryParam("groupBy", "engine").queryParam("since", "yesterday")
                .when().get(SUMMARY).then().statusCode(400);
    }

    private static long subject(String name, String... labels) {
        long id = TestFixtures.createSubject("ai-sum/" + UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> {
            SubjectEntity s = SubjectEntity.findById(id);
            s.name = name;
            s.labels.addAll(List.of(labels));
        });
        return id;
    }

    private static long definition(String name) {
        return TestFixtures.createWaitDefinition(name);
    }

    private static void usage(Long subjectId, Long definitionId, String engine, String model, String actionType,
                              double cost, Instant createdOn) {
        QuarkusTransaction.requiringNew().run(() -> {
            AiUsageEntity u = new AiUsageEntity();
            u.invocationType = "task";
            u.subjectId = subjectId;
            u.workflowDefinitionId = definitionId;
            u.engine = engine;
            u.model = model;
            u.actionType = actionType;
            u.costUsd = cost;
            u.inputTokens = 10L;
            u.outputTokens = 5L;
            u.createdOn = createdOn;
            u.persist();
        });
    }
}
