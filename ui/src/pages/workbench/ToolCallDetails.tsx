import type { ReactNode } from "react";
import { Light as SyntaxHighlighter } from "react-syntax-highlighter";
import json from "react-syntax-highlighter/dist/esm/languages/hljs/json";
import { stackoverflowDark, stackoverflowLight } from "react-syntax-highlighter/dist/esm/styles/hljs";
import { useEffectiveTheme } from "../../hooks/useTheme";
import { opsOf, summarizeOp, unwrapResult, type WorkbenchTool } from "./toolCards";

SyntaxHighlighter.registerLanguage("json", json);

/**
 * Parses text as a JSON object or array.
 * @param text the text
 * @returns the parsed value, or undefined when the text is not a JSON object or array
 */
export function parseJsonValue(text: string | undefined): unknown {
    const trimmed: string = (text ?? "").trim();
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
        return undefined;
    }
    try {
        return JSON.parse(trimmed);
    } catch {
        return undefined;
    }
}

function asValue(value: unknown): unknown {
    return typeof value === "string" ? parseJsonValue(value) ?? value : value;
}

function isEmpty(value: unknown): boolean {
    return value === undefined || value === null || value === ""
        || (typeof value === "object" && Object.keys(value as object).length === 0);
}

/** A labelled section of a card's details. */
function Section({ title, children, danger = false }: { title: string; children: ReactNode; danger?: boolean }) {
    return (
        <div className="axiom-tool-details__section">
            <div className={`axiom-tool-details__label${danger ? " axiom-tool-details__label--danger" : ""}`}>{title}</div>
            {children}
        </div>
    );
}

/** Pretty-printed, highlighted JSON. */
function JsonBlock({ value }: { value: unknown }) {
    const theme = useEffectiveTheme();
    return (
        <div className="axiom-tool-details__code">
            <SyntaxHighlighter language="json" style={theme === "dark" ? stackoverflowDark : stackoverflowLight}
                wrapLongLines customStyle={{ margin: 0, padding: "6px 8px", fontSize: "12px" }}>
                {JSON.stringify(value, null, 2)}
            </SyntaxHighlighter>
        </div>
    );
}

/** Text shown as JSON when it is JSON, otherwise preformatted. */
function TextOrJson({ text, danger = false }: { text: string; danger?: boolean }) {
    const value: unknown = parseJsonValue(text);
    if (value !== undefined) {
        return <JsonBlock value={value} />;
    }
    return <pre className={`axiom-tool-details__text${danger ? " axiom-tool-details__text--danger" : ""}`}>{text}</pre>;
}

/** Problem lines (`- [SEVERITY] path code: message`) from an item write result. */
function problemLines(result: string): string[] {
    return result.split("\n").map((l) => l.trim()).filter((l) => l.startsWith("- ["))
        .map((l) => l.substring(2));
}

/** Props for {@link ToolCallDetails}. */
export interface ToolCallDetailsProps {
    tool: WorkbenchTool | null;
    input: Record<string, unknown> | undefined;
    result: string | undefined;
    isError?: boolean;
}

/**
 * The unpacked details of one workbench tool call: the meaningful parts of its input and result, laid out for
 * the call's type (e.g. the item written and its problems, the workflow changes, the suggested inputs).
 */
export function ToolCallDetails({ tool, input, result, isError = false }: ToolCallDetailsProps) {
    const text: string = result !== undefined ? unwrapResult(result) : "";
    if (isError) {
        return <Section title="Error" danger><TextOrJson text={text} danger /></Section>;
    }
    switch (tool) {
        case "upsert_item": {
            const problems: string[] = problemLines(text);
            const status: string = text.split("\n")[0] ?? "";
            return (
                <>
                    <Section title="Result"><pre className="axiom-tool-details__text">{status}</pre></Section>
                    {problems.length > 0 && (
                        <Section title="Problems">
                            <ul className="axiom-tool-details__list">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
                        </Section>
                    )}
                    {!isEmpty(input?.item) && <Section title="Item"><JsonBlock value={asValue(input?.item)} /></Section>}
                </>
            );
        }
        case "propose_workflow_changes": {
            const summaries: string[] = opsOf(input).map(summarizeOp);
            const rejected: boolean = text.startsWith("REJECTED");
            return (
                <>
                    {summaries.length > 0 && (
                        <Section title="Changes">
                            <ul className="axiom-tool-details__list">{summaries.map((s, i) => <li key={i}>{s}</li>)}</ul>
                        </Section>
                    )}
                    <Section title="Result" danger={rejected}>
                        <pre className={`axiom-tool-details__text${rejected ? " axiom-tool-details__text--danger" : ""}`}>{text}</pre>
                    </Section>
                    {!isEmpty(input?.changeSet) && (
                        <Section title="Change set"><JsonBlock value={asValue(input?.changeSet)} /></Section>
                    )}
                </>
            );
        }
        case "suggest_test":
            return (
                <Section title={input?.type === "tool" ? "Suggested parameters" : "Suggested inputs"}>
                    <JsonBlock value={asValue(input?.inputs) ?? {}} />
                </Section>
            );
        case "evaluate_expression": {
            const ok: RegExpMatchArray | null = text.match(/^OK \(([^)]*)\): ([\s\S]*)$/);
            return (
                <>
                    <Section title="Expression"><pre className="axiom-tool-details__text">{String(input?.expression ?? "")}</pre></Section>
                    <Section title="Scope">
                        <pre className="axiom-tool-details__text">
                            {String(input?.scope ?? "")}{input?.eventId ? ` · event ${String(input.eventId)}` : ""}
                        </pre>
                    </Section>
                    {!isEmpty(input?.context) && <Section title="Context"><JsonBlock value={asValue(input?.context)} /></Section>}
                    {ok
                        ? <Section title={`Value (${ok[1]})`}><TextOrJson text={ok[2]} /></Section>
                        : <Section title="Error" danger><TextOrJson text={text.replace(/^ERROR: /, "")} danger /></Section>}
                </>
            );
        }
        case "list_stream_events": {
            const lines: string[] = text.split("\n").filter((l) => l.trim() !== "" && l !== "No stream events found.");
            const filters: Record<string, unknown> = Object.fromEntries(
                Object.entries(input ?? {}).filter(([, v]) => !isEmpty(v)));
            return (
                <>
                    {!isEmpty(filters) && <Section title="Filters"><JsonBlock value={filters} /></Section>}
                    <Section title="Events">
                        {lines.length === 0 ? <pre className="axiom-tool-details__text">No stream events found.</pre>
                            : <ul className="axiom-tool-details__list axiom-tool-details__list--mono">
                                {lines.map((l, i) => <li key={i}>{l}</li>)}
                            </ul>}
                    </Section>
                </>
            );
        }
        default: {
            const args: Record<string, unknown> = Object.fromEntries(
                Object.entries(input ?? {}).filter(([, v]) => !isEmpty(v)));
            return (
                <>
                    {!isEmpty(args) && <Section title="Input"><JsonBlock value={args} /></Section>}
                    {text !== "" && <Section title="Result"><TextOrJson text={text} /></Section>}
                </>
            );
        }
    }
}
