package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the optional MCP runtime checks (npx, uvx, docker) run at startup.
 */
class StartupRuntimeChecksTest {

    /** Fake runner: command (joined) to result; unknown commands are "not found". */
    private static StartupCheckService.CommandRunner runner(Map<String, StartupCheckService.CommandResult> results) {
        return command -> {
            StartupCheckService.CommandResult r = results.get(String.join(" ", command));
            if (r == null) {
                throw new IOException("Cannot run program \"" + command.get(0) + "\": No such file or directory");
            }
            return r;
        };
    }

    private static StartupCheckService.CommandResult ok(String output) {
        return new StartupCheckService.CommandResult(0, output, false);
    }

    private static List<StartupCheckService.CheckResult> run(Map<String, StartupCheckService.CommandResult> results) {
        StartupCheckService service = new StartupCheckService();
        service.commandRunner = runner(results);
        service.checkOptionalRuntimes();
        return service.getResults();
    }

    private static StartupCheckService.CheckResult named(List<StartupCheckService.CheckResult> results, String name) {
        return results.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allToolsAvailableReportVersionsAsOptionalOk() {
        List<StartupCheckService.CheckResult> results = run(Map.of(
                "npx --version", ok("10.9.2\n"),
                "uvx --version", ok("uvx 0.12.3 (x86_64-unknown-linux-gnu)\n"),
                "docker --version", ok("Docker version 28.1.1, build 4eba377\n"),
                "docker info --format {{.ServerVersion}}", ok("28.1.1\n")));
        assertEquals(List.of("npx", "uvx", "Docker"), results.stream().map(StartupCheckService.CheckResult::name).toList());
        results.forEach(r -> {
            assertEquals("ok", r.status(), r.toString());
            assertFalse(r.required());
        });
        assertTrue(named(results, "npx").message().contains("10.9.2"));
        assertTrue(named(results, "uvx").message().contains("uvx 0.12.3 is"));
        assertTrue(named(results, "Docker").message().contains("28.1.1"));
    }

    @Test
    void missingToolIsAWarningWithInstallHint() {
        List<StartupCheckService.CheckResult> results = run(Map.of("npx --version", ok("10.9.2")));
        StartupCheckService.CheckResult uvx = named(results, "uvx");
        assertEquals("warning", uvx.status());
        assertTrue(uvx.message().contains("https://docs.astral.sh/uv/"), uvx.message());
        assertTrue(uvx.message().contains("Python"), uvx.message());
        assertEquals("warning", named(results, "Docker").status());
        assertTrue(results.stream().noneMatch(r -> "error".equals(r.status())));
    }

    @Test
    void dockerDaemonNotReachableIsAWarning() {
        List<StartupCheckService.CheckResult> results = run(Map.of(
                "docker --version", ok("Docker version 28.1.1"),
                "docker info --format {{.ServerVersion}}", new StartupCheckService.CommandResult(1,
                        "Cannot connect to the Docker daemon at unix:///var/run/docker.sock", false)));
        StartupCheckService.CheckResult docker = named(results, "Docker");
        assertEquals("warning", docker.status());
        assertTrue(docker.message().contains("daemon"), docker.message());
        assertTrue(docker.message().contains("28.1.1"), docker.message());
    }

    @Test
    void timeoutIsAWarning() {
        List<StartupCheckService.CheckResult> results = run(Map.of(
                "npx --version", new StartupCheckService.CommandResult(-1, "", true)));
        StartupCheckService.CheckResult npx = named(results, "npx");
        assertEquals("warning", npx.status());
        assertTrue(npx.message().contains("timed out"), npx.message());
    }

    @Test
    void runtimeAvailabilityIsDerivedFromTheChecks() {
        StartupCheckService service = new StartupCheckService();
        service.commandRunner = runner(Map.of(
                "npx --version", ok("10.9.2"),
                "docker --version", ok("Docker version 28.1.1"),
                "docker info --format {{.ServerVersion}}", new StartupCheckService.CommandResult(1, "down", false)));
        service.checkOptionalRuntimes();
        assertEquals(Map.of("node", true, "uv", false, "docker", false), service.runtimeAvailability());
    }
}
