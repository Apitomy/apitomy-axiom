import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";

interface NodeCodeTabProps {
    value: string;
    onChange: (value: string) => void;
    /** Makes the code editor read-only. */
    readOnly?: boolean;
}

/**
 * Editor tab for the JavaScript module of a "node" execution mode action type.
 */
export function NodeCodeTab({ value, onChange, readOnly = false }: NodeCodeTabProps) {
    const effectiveTheme = useEffectiveTheme();
    return (
        <div>
            <p className="axiom-text-subtle" style={{ marginBottom: "12px" }}>
                Export <code>async function run(inputs, ctx)</code> and return an object with the declared
                outputs. <code>inputs</code> holds the declared inputs; <code>ctx</code> provides{" "}
                <code>workDir</code>, <code>apiUrl</code>, <code>taskId</code>, <code>log(...)</code> and{" "}
                <code>exec(cmd, args, options)</code>. Only Node.js built-in modules may be imported.
            </p>
            <CodeEditor
                code={value}
                isReadOnly={readOnly}
                onChange={(v) => onChange(v)}
                language={Language.javascript}
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
                height="500px"
            />
        </div>
    );
}
