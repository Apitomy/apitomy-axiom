package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.DraftTestRunner;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests test runs whose runner fails unexpectedly or blocks, using a mocked {@link DraftTestRunner}.
 */
@QuarkusTest
class CompositionTestRunsFailureTest {

    static final String BASE = CompositionTestRunsResourceTest.BASE;

    @InjectMock
    DraftTestRunner runner;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void runnerExceptionEndsTheRunAsError() {
        when(runner.runActionType(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        long id = CompositionsResourceTest.createComposition();
        long itemId = CompositionTestRunsResourceTest.scriptActionType(id);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "bob")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("status", equalTo("error"))
                .body("message", equalTo("The test run failed unexpectedly: boom"));
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("[0].status", equalTo("error"));
    }

    @Test
    void thirdConcurrentRunIs429() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(runner.runActionType(any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new DraftTestRunner.Result("passed", Map.of(), "", "", 0, 1, List.of(), null, null);
        });
        long id = CompositionsResourceTest.createComposition();
        long itemId = CompositionTestRunsResourceTest.scriptActionType(id);
        Runnable post = () -> given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "b")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200);
        CompletableFuture<Void> first = CompletableFuture.runAsync(post);
        CompletableFuture<Void> second = CompletableFuture.runAsync(post);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "b")))
                    .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(429)
                    .body("message", equalTo(TooManyTestRunsException.MESSAGE));
        } finally {
            release.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "b")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200);
    }
}
