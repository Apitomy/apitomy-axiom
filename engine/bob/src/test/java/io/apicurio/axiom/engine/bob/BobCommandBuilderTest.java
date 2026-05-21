package io.apicurio.axiom.engine.bob;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BobCommandBuilderTest {

    @Test
    void testMinimalCommand() {
        List<String> cmd = BobCommandBuilder.create("Hello").build();

        assertTrue(cmd.contains("bob"));
        assertTrue(cmd.contains("--auth-method"));
        assertTrue(cmd.contains("api-key"));
        assertTrue(cmd.contains("-p"));
        assertTrue(cmd.contains("Hello"));
        assertTrue(cmd.contains("--yolo"));
    }

    @Test
    void testSystemPromptPrepended() {
        List<String> cmd = BobCommandBuilder.create("Do the thing")
                .systemPrompt("You are a helpful assistant")
                .build();

        int promptIndex = cmd.indexOf("-p");
        String fullPrompt = cmd.get(promptIndex + 1);
        assertTrue(fullPrompt.startsWith("You are a helpful assistant"),
                "System prompt should be at the start");
        assertTrue(fullPrompt.contains("---"),
                "Should have separator between system prompt and user prompt");
        assertTrue(fullPrompt.contains("Do the thing"),
                "User prompt should be present");
    }

    @Test
    void testNoSystemPrompt() {
        List<String> cmd = BobCommandBuilder.create("Just a prompt").build();

        int promptIndex = cmd.indexOf("-p");
        String fullPrompt = cmd.get(promptIndex + 1);
        assertEquals("Just a prompt", fullPrompt);
    }

    @Test
    void testYoloDisabled() {
        List<String> cmd = BobCommandBuilder.create("Read only task")
                .yolo(false)
                .build();

        assertFalse(cmd.contains("--yolo"));
    }

    @Test
    void testYoloEnabledByDefault() {
        List<String> cmd = BobCommandBuilder.create("Write task").build();

        assertTrue(cmd.contains("--yolo"));
    }
}
