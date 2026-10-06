package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link ValidationContextFactory} merges database and pending action type inputs.
 */
@QuarkusTest
class ValidationContextFactoryTest {

    @Inject
    ValidationContextFactory factory;

    @Inject
    ObjectMapper objectMapper;

    /**
     * A pending action type in a pack replaces the database declaration of the same name.
     *
     * @throws Exception on JSON parse failure
     */
    @Test
    void pendingActionTypeInputsOverrideDatabase() throws Exception {
        String name = "ctx-factory-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = name;
            at.executionMode = "script";
            at.inputs = new ArrayList<>(List.of(new ActionTypeField("old", "string", true, null)));
            at.persist();
        });

        assertEquals(Map.of("old", true), factory.databaseOnly().actionTypeInputs().get(name));

        JsonNode pack = objectMapper.readTree("{\"actionTypes\": [{\"name\": \"" + name
                + "\", \"inputs\": [{\"name\": \"fresh\", \"required\": false}]}]}");
        assertEquals(Map.of("fresh", false), factory.forPack(pack).actionTypeInputs().get(name));
    }
}
