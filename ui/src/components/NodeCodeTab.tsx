import { Language } from "@patternfly/react-code-editor";
import { EditableCodeEditor } from "./EditableCodeEditor";
import { useEffectiveTheme } from "../hooks/useTheme";

interface NodeCodeTabProps {
    value: string;
    onChange: (value: string) => void;
    /** Makes the code editor read-only. */
    readOnly?: boolean;
    /** Fill the parent's height (the parent must be a sized flex column) instead of a fixed 500px. */
    fillHeight?: boolean;
}

/**
 * Editor tab for the JavaScript module of a "node" execution mode action type.
 */
export function NodeCodeTab({ value, onChange, readOnly = false, fillHeight = false }: NodeCodeTabProps) {
    const effectiveTheme = useEffectiveTheme();
    return (
        <div className={fillHeight ? "axiom-node-code axiom-node-code--fill" : "axiom-node-code"}>
            <p className="axiom-text-subtle" style={{ marginBottom: "12px" }}>
                Export <code>async function run(inputs, ctx)</code> and return an object with the declared
                outputs. <code>inputs</code> holds the declared inputs; <code>ctx</code> provides{" "}
                <code>workDir</code>, <code>apiUrl</code>, <code>taskId</code>, <code>log(...)</code> and{" "}
                <code>exec(cmd, args, options)</code>. Only Node.js built-in modules may be imported.
            </p>
            <EditableCodeEditor
                code={value}
                isReadOnly={readOnly}
                onChange={(v) => onChange(v)}
                language={Language.javascript}
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
                height={fillHeight ? "100%" : "500px"}
            />
        </div>
    );
}
