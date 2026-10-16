package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.MissingSecretException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Secrets reach a process only when its environment declares them; nothing is injected by default, and a
 * declared secret that does not exist fails the run.
 */
@QuarkusTest
class SecretInjectionTest {

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    EnvironmentResolver environmentResolver;

    @Inject
    EncryptionService encryptionService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void createSecrets() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String name : new String[] {"SIT_ALPHA", "SIT_BETA"}) {
                SecretEntity secret = new SecretEntity();
                secret.name = name;
                secret.encryptedValue = encryptionService.encrypt(name.toLowerCase() + "-value");
                secret.persist();
            }
        });
    }

    @AfterEach
    void deleteSecrets() {
        QuarkusTransaction.requiringNew().run(() -> SecretEntity.delete("name like ?1", "SIT_%"));
    }

    private static ActionTypeEntity script(String environmentJson) {
        ActionTypeEntity actionType = new ActionTypeEntity();
        actionType.name = "sit-script";
        actionType.executionMode = "script";
        actionType.environment = environmentJson;
        actionType.scriptTemplate = "#!/bin/bash\n"
                + "printf '{\"alpha\":\"%s\",\"beta\":\"%s\",\"token\":\"%s\"}' "
                + "\"${SIT_ALPHA:-none}\" \"${SIT_BETA:-none}\" \"${TOKEN:-none}\"\n";
        return actionType;
    }

    private Map<?, ?> run(ActionTypeEntity actionType) throws Exception {
        ScriptExecutionService.ActionRun run = scriptExecutionService.runScriptAction(actionType, Map.of(), null,
                "sit");
        assertEquals(0, run.exitCode(), run.stderr());
        return MAPPER.readValue(run.output(), Map.class);
    }

    @Test
    void aScriptWithoutAnEnvironmentGetsNoSecrets() throws Exception {
        Map<?, ?> output = run(script(null));
        assertEquals("none", output.get("alpha"));
        assertEquals("none", output.get("beta"));
    }

    @Test
    void aScriptGetsOnlyTheSecretsItDeclares() throws Exception {
        Map<?, ?> output = run(script("{\"TOKEN\": \"${secret:SIT_ALPHA}\"}"));
        assertEquals("sit_alpha-value", output.get("token"));
        assertEquals("none", output.get("alpha"));
        assertEquals("none", output.get("beta"));
    }

    @Test
    void aMissingDeclaredSecretFailsTheRun() {
        MissingSecretException e = assertThrows(MissingSecretException.class,
                () -> run(script("{\"TOKEN\": \"${secret:SIT_MISSING}\"}")));
        assertEquals("Secret 'SIT_MISSING' does not exist", e.getMessage());
    }

    @Test
    void anAgentProcessGetsOnlyItsDeclaredSecrets() {
        Map<String, String> none = QuarkusTransaction.requiringNew().call(() ->
                taskExecutionService.buildEnvironment(null));
        assertFalse(none.containsKey("SIT_ALPHA"));
        assertFalse(none.containsKey("SIT_BETA"));

        Map<String, String> declared = QuarkusTransaction.requiringNew().call(() ->
                taskExecutionService.buildEnvironment("{\"GH\": \"${secret:SIT_BETA}\"}"));
        assertEquals(Map.of("GH", "sit_beta-value"), declared);
    }

    @Test
    void resolvingAMissingSecretThrows() {
        assertThrows(MissingSecretException.class, () -> QuarkusTransaction.requiringNew().call(() ->
                environmentResolver.resolve("{\"X\": \"prefix-${secret:SIT_NOPE}\"}")));
    }
}
