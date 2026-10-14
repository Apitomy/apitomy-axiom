import { useState } from "react";
import {
    Alert, Button, ExpandableSection, Label, Modal, ModalBody, ModalFooter, ModalHeader, Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import type { CompositionTestRun } from "../../config/compositions";
import { useEffectiveTheme } from "../../hooks/useTheme";

/** Maximum height of a code block in the dialog; longer content scrolls inside the editor. */
const MAX_LINES: number = 20;

/**
 * Pretty-prints text when it is a JSON object or array.
 * @param text the text
 * @returns the pretty-printed JSON, or null when the text is not a JSON object or array
 */
export function prettyJson(text: string | undefined): string | null {
    const trimmed: string = (text ?? "").trim();
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
        return null;
    }
    try {
        return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
        return null;
    }
}

function lineHeight(code: string): string {
    const lines: number = Math.min(MAX_LINES, Math.max(1, code.split("\n").length));
    return `${lines * 19 + 12}px`;
}

/** Read-only, highlighted code block sized to its content (up to {@link MAX_LINES} lines). */
function CodeBlock({ code, language, label }: { code: string; language: Language; label: string }) {
    const theme = useEffectiveTheme();
    return (
        <div aria-label={label} className="axiom-test-run__code">
            <CodeEditor code={code} language={language} isReadOnly isDarkTheme={theme === "dark"}
                isCopyEnabled height={lineHeight(code)}
                options={{ wordWrap: "on", scrollBeyondLastLine: false, minimap: { enabled: false } }} />
        </div>
    );
}

/** A section for text output: highlighted as JSON when it is JSON, otherwise plain text. */
function OutputSection({ title, text }: { title: string; text: string | undefined }) {
    if (!text) {
        return null;
    }
    const json: string | null = prettyJson(text);
    return (
        <section className="axiom-test-run__section">
            <Title headingLevel="h3" size="md">{title}</Title>
            <CodeBlock code={json ?? text} language={json ? Language.json : Language.plaintext} label={title} />
        </section>
    );
}

/** Props for {@link TestRunDetailModal}. */
export interface TestRunDetailModalProps {
    run: CompositionTestRun;
    onClose(): void;
    /** Opens the run's item in a tab. */
    onOpenItem(itemId: number): void;
    /** Runs an AI-suggested test (shown for suggested runs only). */
    onRunSuggestion?(run: CompositionTestRun): void;
}

const STATUS_COLOR: Record<CompositionTestRun["status"], "blue" | "green" | "red" | "orange" | "purple"> = {
    running: "blue", passed: "green", failed: "red", error: "orange", suggested: "purple",
};

/**
 * Shows one test run: its status and timing, inputs and outputs (JSON highlighted), problems, stdout,
 * stderr and the resolved script.
 */
export function TestRunDetailModal({ run, onClose, onOpenItem, onRunSuggestion }: TestRunDetailModalProps) {
    const [scriptOpen, setScriptOpen] = useState<boolean>(false);
    const kind: string = run.itemType === "tool" ? "Tool" : "Action type";
    const problems: string[] = run.problems ?? [];
    return (
        <Modal isOpen variant="large" onClose={onClose} aria-labelledby="test-run-detail-title"
            elementToFocus="#test-run-detail-primary">
            <ModalHeader title={`Test run: ${run.itemName}`} labelId="test-run-detail-title" />
            <ModalBody>
                <dl className="axiom-test-run__meta">
                    <div>
                        <dt>Status</dt>
                        <dd><Label isCompact color={STATUS_COLOR[run.status]}>{run.status}</Label></dd>
                    </div>
                    <div>
                        <dt>{kind}</dt>
                        <dd>
                            <Button variant="link" isInline onClick={() => { onOpenItem(run.itemId); onClose(); }}>
                                {run.itemName}
                            </Button>
                        </dd>
                    </div>
                    <div>
                        <dt>Started</dt>
                        <dd>{new Date(run.startedAt).toLocaleString()}</dd>
                    </div>
                    <div>
                        <dt>By</dt>
                        <dd>{run.author === "ai" ? "AI" : "You"}</dd>
                    </div>
                    {run.durationMs !== undefined && run.status !== "suggested" && (
                        <div>
                            <dt>Duration</dt>
                            <dd>{run.durationMs} ms</dd>
                        </div>
                    )}
                    {run.exitCode !== undefined && (
                        <div>
                            <dt>Exit code</dt>
                            <dd>{run.exitCode}</dd>
                        </div>
                    )}
                </dl>

                {run.message && <Alert isInline variant="warning" title={run.message} className="axiom-test-run__section" />}
                {problems.length > 0 && (
                    <Alert isInline variant="danger" title="Problems" className="axiom-test-run__section">
                        <ul>{problems.map((p, i) => <li key={`${i}-${p}`}>{p}</li>)}</ul>
                    </Alert>
                )}

                <section className="axiom-test-run__section">
                    <Title headingLevel="h3" size="md">{run.itemType === "tool" ? "Parameters" : "Inputs"}</Title>
                    <CodeBlock code={JSON.stringify(run.inputs ?? {}, null, 2)} language={Language.json}
                        label={run.itemType === "tool" ? "Parameters" : "Inputs"} />
                </section>
                {run.outputs && (
                    <section className="axiom-test-run__section">
                        <Title headingLevel="h3" size="md">Outputs</Title>
                        <CodeBlock code={JSON.stringify(run.outputs, null, 2)} language={Language.json} label="Outputs" />
                    </section>
                )}
                <OutputSection title="stdout" text={run.stdout} />
                <OutputSection title="stderr" text={run.stderr} />
                {run.resolvedScript && (
                    <ExpandableSection className="axiom-test-run__section" toggleText="Resolved script"
                        isExpanded={scriptOpen} onToggle={(_e, open) => setScriptOpen(open)}>
                        <CodeBlock code={run.resolvedScript}
                            language={run.resolvedScript.startsWith("export ") ? Language.javascript : Language.shell}
                            label="Resolved script" />
                    </ExpandableSection>
                )}
            </ModalBody>
            <ModalFooter>
                {run.status === "suggested" && onRunSuggestion && (
                    <Button id="test-run-detail-primary" variant="primary"
                        onClick={() => { onClose(); onRunSuggestion(run); }}>Run…</Button>
                )}
                <Button id={run.status === "suggested" && onRunSuggestion ? undefined : "test-run-detail-primary"}
                    variant={run.status === "suggested" ? "link" : "primary"} onClick={onClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}
