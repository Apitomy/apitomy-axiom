/** Maximum length of an MCP server name (mirrors McpServerService.MAX_NAME_LENGTH). */
export const MCP_SERVER_NAME_MAX = 64;

const NAME_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?$/;

/**
 * Checks an MCP server name against the server-side rules: letters, digits, '-' and '_', starting and
 * ending with a letter or digit, no '__' (it separates the parts of mcp__<server>__<tool>).
 *
 * @param name the name to check (surrounding whitespace is ignored)
 * @returns an error message, or null when the name is valid or still empty
 */
export function mcpServerNameError(name: string): string | null {
    const trimmed = name.trim();
    if (!trimmed) return null;
    if (trimmed.length > MCP_SERVER_NAME_MAX) {
        return `Use at most ${MCP_SERVER_NAME_MAX} characters.`;
    }
    if (trimmed.includes("__")) {
        return "Do not use '__': it separates the parts of mcp__<server>__<tool>.";
    }
    if (!NAME_PATTERN.test(trimmed)) {
        return "Use letters, digits, '-' and '_', and start and end with a letter or digit.";
    }
    return null;
}
