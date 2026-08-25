/**
 * Registers a Monaco completion provider that suggests placeholder
 * substitutions (e.g. {{source}}, {{actionTypes}}) when the user
 * types "{{" in the editor.
 *
 * Usage with PatternFly CodeEditor:
 * ```tsx
 * <CodeEditor
 *     onEditorDidMount={(editor, monaco) => {
 *         registerPlaceholderCompletions(monaco, "markdown", MANAGER_PLACEHOLDERS);
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

/**
 * Registers a completion provider for the given language that triggers
 * on "{{" and suggests the provided placeholders.
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
    // Disable default word-based suggestions — only show our placeholder completions
    editor.updateOptions({
        wordBasedSuggestions: "off",
        quickSuggestions: false,
    });

    return monaco.languages.registerCompletionItemProvider(language, {
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
}

/**
 * Placeholders available in the Manager prompt template.
 */
export const MANAGER_PLACEHOLDERS: PlaceholderItem[] = [
    { name: "actionTypes", description: "Formatted list of all configured action types with names and descriptions" },
    { name: "agents", description: "Formatted list of all configured agents with names, types, and capabilities" },
    { name: "source", description: "Event source (e.g. 'github')" },
    { name: "eventType", description: "Event type (e.g. 'issue-created', 'comment-added')" },
    { name: "issueRef", description: "Issue reference (e.g. 'owner/repo#42')" },
    { name: "repository", description: "Repository (e.g. 'owner/repo')" },
    { name: "payload", description: "Raw event payload JSON" },
    { name: "projectContext", description: "Existing project details and recent task history" },
];

/**
 * Placeholders available in the Action Type prompt template.
 */
export const ACTION_TYPE_PLACEHOLDERS: PlaceholderItem[] = [
    { name: "managerInput", description: "Instructions/context from the Manager's decision" },
    { name: "actionType", description: "The action type name" },
    { name: "ref", description: "Project reference (e.g. 'owner/repo#42' or 'CVE-2024-12345')" },
    { name: "repository", description: "Repository (e.g. 'owner/repo')" },
    { name: "projectName", description: "Project name" },
];

/**
 * Placeholders available in the Report Definition prompt template.
 */
export const REPORT_PLACEHOLDERS: PlaceholderItem[] = [
    { name: "repositories", description: "Comma-separated list of repositories to scan" },
    { name: "timeRangeStart", description: "Start of the report time window (ISO date)" },
    { name: "timeRangeEnd", description: "End of the report time window (ISO date)" },
    { name: "timeWindow", description: "Human-readable time window description" },
];
