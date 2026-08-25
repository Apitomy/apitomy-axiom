package io.apitomy.axiom.agents.spi;

/**
 * Result of a single agent startup health check.
 *
 * @param name    the check name (e.g. "Claude Code CLI")
 * @param status  the check status: "ok", "warning", or "error"
 * @param message a human-readable description of the check result
 */
public record AgentCheckResult(String name, String status, String message) {
}
