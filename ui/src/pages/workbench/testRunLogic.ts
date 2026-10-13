/** A field of the test form: an action type input or a tool parameter. */
export interface TestField {
    name: string;
    type: string;
    required: boolean;
    description?: string;
}

/** Message shown for action types that cannot be test-run. */
export const AGENT_UNSUPPORTED: string = "Test runs of AI Agent action types are not supported yet.";

/**
 * Lists the fields to ask for: an action type's inputs or a tool's parameters.
 * @param type the item type
 * @param content the draft content
 * @returns the fields, in declaration order
 */
export function testFields(type: "action-type" | "tool", content: Record<string, unknown>): TestField[] {
    const raw: unknown = type === "tool" ? content.parameters : content.inputs;
    if (!Array.isArray(raw)) {
        return [];
    }
    return raw
        .filter((f: unknown): f is Record<string, unknown> =>
            !!f && typeof f === "object" && typeof (f as Record<string, unknown>).name === "string"
            && (f as Record<string, unknown>).name !== "")
        .map((f: Record<string, unknown>) => ({
            name: f.name as string,
            type: typeof f.type === "string" ? f.type : "string",
            required: f.required === true,
            description: typeof f.description === "string" ? f.description : undefined,
        }));
}

/**
 * Explains why an item cannot be test-run.
 * @param type the item type
 * @param content the draft content
 * @returns the reason, or null when it can run
 */
export function testUnsupportedReason(type: "action-type" | "tool", content: Record<string, unknown>): string | null {
    if (type === "tool") {
        return null;
    }
    return content.executionMode === "node" || content.executionMode === "script" ? null : AGENT_UNSUPPORTED;
}

/**
 * Parses the text typed for a field.
 * @param field the field
 * @param text the typed text
 * @returns the value, nothing for empty text, or an error message
 */
export function parseFieldValue(field: TestField, text: string): { value?: unknown; error?: string } {
    if (text.trim() === "") {
        return {};
    }
    switch (field.type) {
        case "number": {
            const n: number = Number(text);
            return Number.isFinite(n) ? { value: n } : { error: "Enter a number." };
        }
        case "boolean":
            return text.trim() === "true" ? { value: true }
                : text.trim() === "false" ? { value: false } : { error: "Enter true or false." };
        case "array":
        case "object": {
            const message: string = field.type === "array" ? "Enter a JSON array." : "Enter a JSON object.";
            try {
                const parsed: unknown = JSON.parse(text);
                const ok: boolean = field.type === "array" ? Array.isArray(parsed)
                    : !!parsed && typeof parsed === "object" && !Array.isArray(parsed);
                return ok ? { value: parsed } : { error: message };
            } catch {
                return { error: message };
            }
        }
        default:
            return { value: text };
    }
}
