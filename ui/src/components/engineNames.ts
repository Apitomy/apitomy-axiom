const ENGINE_LABELS: Record<string, string> = {
    "claude-code": "Claude Code",
    "opencode": "OpenCode",
    "copilot": "GitHub Copilot CLI",
};

/** PatternFly label colours used for engine badges. */
export type EngineLabelColor = "orangered" | "purple" | "yellow" | "grey";

const ENGINE_COLORS: Record<string, EngineLabelColor> = {
    "claude-code": "orangered",
    "opencode": "purple",
    "copilot": "yellow",
};

/**
 * Returns the badge colour for an AI engine id, so each engine is recognisable at a glance.
 * The colours avoid those already used next to engine badges (status, plan mode, project, model).
 *
 * @param engine the engine id (e.g. `claude-code`, `opencode`)
 * @returns the label colour; `grey` for unknown or empty engines
 */
export function engineColor(engine?: string | null): EngineLabelColor {
    if (!engine) return "grey";
    return Object.hasOwn(ENGINE_COLORS, engine) ? ENGINE_COLORS[engine] : "grey";
}

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
