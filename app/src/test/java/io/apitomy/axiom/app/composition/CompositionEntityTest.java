package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the composition entities persist and load with their defaults.
 */
@QuarkusTest
class CompositionEntityTest {

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void persistsCompositionItemAndRevision() {
        long itemId = QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity composition = new CompositionEntity();
            composition.name = "c1";
            composition.createdOn = Instant.now();
            composition.updatedOn = composition.createdOn;
            composition.persist();

            CompositionItemEntity item = new CompositionItemEntity();
            item.compositionId = composition.id;
            item.itemType = "tool";
            item.name = "t1";
            item.content = "{\"name\":\"t1\"}";
            item.contentRevision = "sha256:x";
            item.lastAuthor = "user";
            item.createdOn = Instant.now();
            item.updatedOn = item.createdOn;
            item.persist();

            CompositionRevisionEntity revision = new CompositionRevisionEntity();
            revision.itemId = item.id;
            revision.content = item.content;
            revision.contentRevision = item.contentRevision;
            revision.author = "user";
            revision.createdOn = Instant.now();
            revision.persist();
            return item.id;
        });

        QuarkusTransaction.requiringNew().run(() -> {
            CompositionItemEntity item = CompositionItemEntity.findById(itemId);
            CompositionEntity composition = CompositionEntity.findById(item.compositionId);
            assertEquals(CompositionEntity.TURN_IDLE, composition.turnState);
            assertEquals(CompositionEntity.APPLY_LIVE, composition.applyMode);
            assertEquals(1, CompositionRevisionEntity.count("itemId", itemId));
        });
    }
}
