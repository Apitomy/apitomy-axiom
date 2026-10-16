/**
 * Registers a Monaco completion provider that suggests placeholder
 * substitutions (e.g. {{inputs.ref}}) when the user
 * types "{{" in the editor.
 *
 * Usage with PatternFly CodeEditor:
 * ```tsx
 * <CodeEditor
 *     onEditorDidMount={(editor, monaco) => {
 *         registerPlaceholderCompletions(editor, monaco, "markdown", inputPlaceholderItems(inputs));
 *     }}
 * />
 * ```
 */

export interface PlaceholderItem {
    /** The placeholder name without braces (e.g. "source") */
    name: string;
    /** Description shown in the completion popup */
    description: string;
}

// Track registered providers per language to prevent duplicate registrations
// across component re-mounts. Key: "language:placeholderNames"
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const registeredProviders = new Map<string, any>();

/**
 * Registers a completion provider for the given language that triggers
 * on "{{" and suggests the provided placeholders. Safe to call multiple
 * times — duplicate registrations for the same language and placeholder
 * set are silently ignored.
 *
 * @param monaco the Monaco namespace object
 * @param language the language ID (e.g. "markdown", "shell")
 * @param placeholders the list of available placeholders
 * @returns a disposable that can be used to unregister the provider
 */
export function registerPlaceholderCompletions(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    editor: any,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    monaco: any,
    language: string,
    placeholders: PlaceholderItem[]
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
): any {
    // Guard against duplicate registration. If the placeholder set changed
    // (e.g., inputs were added/removed), dispose the old provider first.
    const key = `${language}:${placeholders.map(p => p.name).join(",")}`;
    const langPrefix = `${language}:`;

    // Dispose any existing provider for this language with a different placeholder set
    for (const [existingKey, disposable] of registeredProviders.entries()) {
        if (existingKey.startsWith(langPrefix) && existingKey !== key) {
            disposable.dispose();
            registeredProviders.delete(existingKey);
        }
    }

    if (registeredProviders.has(key)) {
        // Still apply editor options on re-mount
        editor.updateOptions({
            wordBasedSuggestions: "off",
            quickSuggestions: false,
        });
        return registeredProviders.get(key);
    }
    // Disable default word-based suggestions — only show our placeholder completions
    editor.updateOptions({
        wordBasedSuggestions: "off",
        quickSuggestions: false,
    });

    const disposable = monaco.languages.registerCompletionItemProvider(language, {
        triggerCharacters: ["{"],
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        provideCompletionItems: (model: any, position: any) => {
            const textUntilPosition = model.getValueInRange({
                startLineNumber: position.lineNumber,
                startColumn: Math.max(1, position.column - 2),
                endLineNumber: position.lineNumber,
                endColumn: position.column,
            });

            // Only trigger after "{{"
            if (!textUntilPosition.endsWith("{{")) {
                return { suggestions: [] };
            }

            // Check if Monaco auto-inserted closing braces after the cursor
            const lineContent = model.getLineContent(position.lineNumber);
            const textAfterCursor = lineContent.substring(position.column - 1);
            const closingBraces = textAfterCursor.startsWith("}}") ? 2 : 0;

            const range = {
                startLineNumber: position.lineNumber,
                startColumn: position.column,
                endLineNumber: position.lineNumber,
                endColumn: position.column + closingBraces,
            };

            const suggestions = placeholders.map((p) => ({
                label: `{{${p.name}}}`,
                kind: monaco.languages.CompletionItemKind.Variable,
                documentation: p.description,
                insertText: `${p.name}}}`,
                range,
            }));

            return { suggestions };
        },
    });

    registeredProviders.set(key, disposable);
    return disposable;
}

/**
 * Builds `inputs.<name>` completion items for an action type's declared inputs.
 * Inputs without a name are skipped.
 *
 * @param inputs the declared inputs
 * @returns one placeholder item per named input, in declaration order
 */
export function inputPlaceholderItems(
    inputs: { name: string; description?: string; type?: string }[]
): PlaceholderItem[] {
    return inputs
        .filter((i) => i.name)
        .map((i) => ({
            name: `inputs.${i.name}`,
            description: i.description || `Input: ${i.name}${i.type ? ` (${i.type})` : ""}`,
        }));
}

/**
 * Environment variables available to every script at runtime. These are
 * documentation entries for the editor help, not `{{…}}` completions.
 */
export const SCRIPT_ENV_VARS: PlaceholderItem[] = [
    { name: "AXIOM_API_URL", description: "Base URL of the Axiom API (e.g. 'http://localhost:8080/api/v1')" },
    { name: "AXIOM_TASK_ID", description: "ID of the task executing this script" },
    { name: "AXIOM_WORK_DIR", description: "Absolute path to the task's working directory" },
];
