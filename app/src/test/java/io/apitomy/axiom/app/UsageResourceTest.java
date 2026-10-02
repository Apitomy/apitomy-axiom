package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Tests for the AI usage REST API endpoint, focusing on the trace ID filter.
 */
@QuarkusTest
class UsageResourceTest {

    private static final String AI_USAGE_PATH = "/api/v1/usage/ai";

    private static final UUID TRACE_A = UUID.fromString("d4d4d4d4-0000-4000-8000-000000000400");

    @BeforeEach
    @Transactional
    void seedAiUsage() {
        // Only seed once — check if our test rows already exist
        if (AiUsageEntity.count("traceId", TRACE_A) > 0) {
            return;
        }
        createUsage(TRACE_A, 0.25);
        createUsage(TRACE_A, 0.50);
        createUsage(null, 1.00);
    }

    private void createUsage(UUID traceId, double costUsd) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "task";
        usage.costUsd = costUsd;
        usage.traceId = traceId;
        usage.createdOn = Instant.now();
        usage.persist();
    }

    @Test
    void testFilterByTraceId() {
        given().queryParam("filterTraceId", TRACE_A.toString())
            .when().get(AI_USAGE_PATH)
            .then().statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.traceId", everyItem(equalTo(TRACE_A.toString())));
    }

    @Test
    void testFilterByMalformedTraceIdReturns400() {
        given().queryParam("filterTraceId", "not-a-uuid")
            .when().get(AI_USAGE_PATH)
            .then().statusCode(400);
    }
}
