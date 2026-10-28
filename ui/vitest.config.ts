import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    server: {
        // toolCards.test.ts reads the workbench MCP server to check every tool has a chat label.
        fs: { allow: [".", "../app/src/main/resources/templates/axiom-workbench-mcp"] },
    },
    test: {
        environment: "jsdom",
        globals: true,
        setupFiles: ["./src/test/setup.ts"],
        css: false,
    },
});
