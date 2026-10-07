package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Items in an assistant session directory are named by their JSON name (or id for event sources), falling
     * back to the file basename when the field is missing or the file is unreadable; pending action type
     * inputs are read from the same files.
     *
     * @param dir temporary session working directory
     * @throws Exception on file write failure
     */
    @Test
    void forDirectoryReadsNamesAndInputsFromSessionFiles(@TempDir Path dir) throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        write(dir, "action-types/file-a.json", "{\"name\": \"at-" + sfx + "\", \"inputs\": "
                + "[{\"name\": \"ref\", \"required\": true}]}");
        write(dir, "action-types/no-name-" + sfx + ".json", "{\"inputs\": []}");
        write(dir, "tools/broken-" + sfx + ".json", "{ not json");
        write(dir, "event-sources/file-es.json", "{\"id\": \"es-" + sfx + "\", \"name\": \"Display Name\"}");

        ValidationContext ctx = factory.forDirectory(dir);

        assertTrue(ctx.actionTypeNames().contains("at-" + sfx));
        assertTrue(ctx.actionTypeNames().contains("no-name-" + sfx));
        assertTrue(ctx.toolNames().contains("broken-" + sfx));
        assertTrue(ctx.eventSourceIds().contains("es-" + sfx));
        assertEquals(Map.of("ref", true), ctx.actionTypeInputs().get("at-" + sfx));
    }

    private static void write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
