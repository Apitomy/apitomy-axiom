const ENGINE_LABELS: Record<string, string> = {
    "claude-code": "Claude Code",
    "opencode": "OpenCode",
    "copilot": "GitHub Copilot CLI",
};

/**
 * Returns the human-readable display name for an AI engine id.
 *
 * @param engine the engine id (e.g. `claude-code`, `opencode`)
 * @returns the display name, the raw id for unknown engines, or `null` when the engine is empty
 */
export function engineDisplayName(engine?: string | null): string | null {
    if (!engine) return null;
    return Object.hasOwn(ENGINE_LABELS, engine) ? ENGINE_LABELS[engine] : engine;
}
