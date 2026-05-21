package io.apicurio.axiom.engine.bob;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the command line for launching an IBM Bob CLI subprocess
 * in non-interactive mode.
 */
public class BobCommandBuilder {

    private String prompt;
    private String systemPrompt;
    private boolean yolo = true;

    public static BobCommandBuilder create(String prompt) {
        BobCommandBuilder builder = new BobCommandBuilder();
        builder.prompt = prompt;
        return builder;
    }

    public BobCommandBuilder systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public BobCommandBuilder yolo(boolean yolo) {
        this.yolo = yolo;
        return this;
    }

    public List<String> build() {
        List<String> cmd = new ArrayList<>();
        cmd.add("bob");

        // Accept license non-interactively and use API key auth
        cmd.add("--accept-license");
        cmd.add("--auth-method");
        cmd.add("api-key");

        // Build the full prompt (system prompt prepended if present)
        String fullPrompt;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullPrompt = systemPrompt + "\n\n---\n\n" + prompt;
        } else {
            fullPrompt = prompt;
        }

        cmd.add("-p");
        cmd.add(fullPrompt);

        if (yolo) {
            cmd.add("--yolo");
        }

        return cmd;
    }
}
