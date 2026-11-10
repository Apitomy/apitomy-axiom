package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.entities.SecretEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REST tests for secret expiration fields and validation.
 */
@QuarkusTest
class SecretsResourceTest {

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> SecretEntity.delete("name like ?1", "SRT_%"));
    }

    private static String body(String name, String expiresOn, Integer warnDays) {
        StringBuilder json = new StringBuilder("{\"name\":\"" + name + "\",\"value\":\"v\"");
        if (expiresOn != null) {
            json.append(",\"expiresOn\":\"").append(expiresOn).append('"');
        }
        if (warnDays != null) {
            json.append(",\"warnDaysBefore\":").append(warnDays);
        }
        return json.append('}').toString();
    }

    private static long create(String name, String expiresOn, Integer warnDays) {
        return given().contentType(ContentType.JSON).body(body(name, expiresOn, warnDays))
                .post("/api/v1/secrets").then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    @Test
    void secretWithoutExpiryHasStatusNone() {
        given().contentType(ContentType.JSON).body(body("SRT_NONE", null, null))
                .post("/api/v1/secrets").then().statusCode(200)
                .body("expiryStatus", equalTo("NONE"))
                .body("expiresOn", nullValue());
    }

    @Test
    void secretExpiringInThreeDaysIsExpiring() {
        String expires = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS).toString();
        given().contentType(ContentType.JSON).body(body("SRT_SOON", expires, null))
                .post("/api/v1/secrets").then().statusCode(200)
                .body("expiryStatus", equalTo("EXPIRING"));
    }

    @Test
    void perSecretWarnDaysIsReturnedAndApplied() {
        String expires = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS).toString();
        given().contentType(ContentType.JSON).body(body("SRT_WARN", expires, 1))
                .post("/api/v1/secrets").then().statusCode(200)
                .body("warnDaysBefore", equalTo(1))
                .body("expiryStatus", equalTo("VALID"));
    }

    @Test
    void pastExpiryIsRejectedOnCreate() {
        String past = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS).toString();
        given().contentType(ContentType.JSON).body(body("SRT_PAST", past, null))
                .post("/api/v1/secrets").then().statusCode(400);
    }

    @Test
    void negativeWarnDaysIsRejected() {
        String expires = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS).toString();
        given().contentType(ContentType.JSON).body(body("SRT_NEG", expires, -1))
                .post("/api/v1/secrets").then().statusCode(400);
    }

    @Test
    void pastExpiryIsRejectedOnUpdate() {
        long id = create("SRT_UPD", null, null);
        String past = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS).toString();
        given().contentType(ContentType.JSON).body(body("SRT_UPD", past, null))
                .put("/api/v1/secrets/" + id).then().statusCode(400);
    }

    @Test
    void updatingAnExpiredSecretWithoutADateClearsTheExpiry() {
        long id = create("SRT_EXP", Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS).toString(), null);
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity s = SecretEntity.findById(id);
            s.expiresOn = Instant.now().minus(Duration.ofDays(1));
        });
        given().contentType(ContentType.JSON).body(body("SRT_EXP", null, null))
                .put("/api/v1/secrets/" + id).then().statusCode(200)
                .body("expiryStatus", equalTo("NONE"));
    }

    @Test
    void updateResetsEventBookkeeping() {
        long id = create("SRT_RST", Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS).toString(), null);
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity s = SecretEntity.findById(id);
            s.expiryWarningSentOn = Instant.now();
            s.expiredEventSentOn = Instant.now();
        });
        String later = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS).toString();
        given().contentType(ContentType.JSON).body(body("SRT_RST", later, null))
                .put("/api/v1/secrets/" + id).then().statusCode(200)
                .body("expiryStatus", equalTo("VALID"));
        SecretEntity after = QuarkusTransaction.requiringNew().call(() -> SecretEntity.findById(id));
        assertNull(after.expiryWarningSentOn);
        assertNull(after.expiredEventSentOn);
    }

    @Test
    void systemConfigExposesWarningDays() {
        given().get("/api/v1/system/config").then().statusCode(200)
                .body("secretExpiryWarningDays", equalTo(7));
    }
}
