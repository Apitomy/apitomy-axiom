/**
 * Returns the PatternFly label colour used to display an action type execution mode.
 *
 * @param mode the execution mode ("agent", "script" or "node")
 * @returns the label colour
 */
export function executionModeColor(mode: string | undefined): "blue" | "purple" | "orange" {
    if (mode === "agent") {
        return "blue";
    }
    if (mode === "node") {
        return "purple";
    }
    return "orange";
}
