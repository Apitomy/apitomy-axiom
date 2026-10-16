package io.apitomy.axiom.core.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeModuleChecks}.
 */
class NodeModuleChecksTest {

    @Test
    void importSpecifiersFindsEverySyntax() {
        String code = String.join("\n",
                "import fs from 'node:fs';",
                "import { join } from \"path\";",
                "import * as os from 'os';",
                "import 'side-effect';",
                "const m = await import('lodash');",
                "const a = require(\"axios\");");

        List<String> specs = NodeModuleChecks.importSpecifiers(code);

        assertEquals(List.of("node:fs", "path", "os", "side-effect", "lodash", "axios"), specs);
    }

    @Test
    void importSpecifiersHandlesMultilineNamedImport() {
        String code = "import {\n  readFile,\n  writeFile\n} from 'node:fs/promises';\n";

        assertEquals(List.of("node:fs/promises"), NodeModuleChecks.importSpecifiers(code));
    }

    @Test
    void isBuiltinRecognisesNodePrefixAndNames() {
        assertTrue(NodeModuleChecks.isBuiltin("node:fs"));
        assertTrue(NodeModuleChecks.isBuiltin("node:anything"));
        assertTrue(NodeModuleChecks.isBuiltin("fs"));
        assertTrue(NodeModuleChecks.isBuiltin("fs/promises"));
        assertTrue(NodeModuleChecks.isBuiltin("child_process"));
        assertFalse(NodeModuleChecks.isBuiltin("lodash"));
        assertFalse(NodeModuleChecks.isBuiltin("axios"));
        assertFalse(NodeModuleChecks.isBuiltin("./local.js"));
    }

    @Test
    void exportsRunRecognisesEachForm() {
        assertTrue(NodeModuleChecks.exportsRun("export async function run(inputs, ctx) {}"));
        assertTrue(NodeModuleChecks.exportsRun("export function run(inputs) {}"));
        assertTrue(NodeModuleChecks.exportsRun("export const run = async () => ({});"));
        assertTrue(NodeModuleChecks.exportsRun("export let run = () => ({});"));
        assertTrue(NodeModuleChecks.exportsRun("async function run() {}\nexport { run };"));
        assertTrue(NodeModuleChecks.exportsRun("export { helper, run as run };"));
        assertFalse(NodeModuleChecks.exportsRun("export function runner() {}"));
        assertFalse(NodeModuleChecks.exportsRun("export { rerun };"));
        assertFalse(NodeModuleChecks.exportsRun("async function run() {}"));
    }

    @Test
    void syntaxErrorDetectsInvalidModule() {
        assumeNode();
        Optional<String> err = NodeModuleChecks.syntaxError("export async function run( {\n");

        assertTrue(err.isPresent());
        assertTrue(err.get().contains("SyntaxError"));
    }

    @Test
    void syntaxErrorEmptyForValidModule() {
        assumeNode();
        Optional<String> err = NodeModuleChecks.syntaxError(
                "import fs from 'node:fs';\nexport async function run(inputs) { return {}; }\n");

        assertTrue(err.isEmpty());
    }

    @Test
    void inputReadsCollectsIdentifiers() {
        String code = "const a = inputs.repo; const b = inputs.issue_id + inputs.repo; myinputs.x;";

        assertEquals(Set.of("repo", "issue_id"), NodeModuleChecks.inputReads(code));
    }

    private static void assumeNode() {
        boolean available;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            available = p.waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "node not available");
    }

    @Test
    void syntaxErrorCachesResultsByCode() {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        String code = "export const x" + System.nanoTime() + " = (;\n";
        int before = NodeModuleChecks.cacheSize();
        Optional<String> first = NodeModuleChecks.syntaxError(code);
        assertEquals(before + 1, NodeModuleChecks.cacheSize());
        Optional<String> second = NodeModuleChecks.syntaxError(code);
        assertEquals(before + 1, NodeModuleChecks.cacheSize());
        assertTrue(first.isPresent());
        assertEquals(first, second);
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
