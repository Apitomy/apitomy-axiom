package io.apitomy.axiom.events.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a single event produced during a dry-run evaluation.
 *
 * @param eventType the classified event type (e.g. "issue-created", "pr-merged")
 * @param issueRef  the issue or PR reference (e.g. "owner/repo#42")
 * @param summary   human-readable summary (e.g. issue title or comment author)
 * @param payload   the wrapped event payload JSON
 */
public record DryRunEvent(String eventType, String issueRef, String summary, JsonNode payload) {
}
