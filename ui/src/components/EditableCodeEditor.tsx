import { useCallback, useEffect, useRef, useState } from "react";
import { CodeEditor, type CodeEditorProps } from "@patternfly/react-code-editor";
import type * as Monaco from "monaco-editor";

/** Upper bound on remembered emitted values; older ones can no longer arrive as echoes in practice. */
const MAX_PENDING: number = 50;

/**
 * Checks whether `code` is a late echo of a value this editor emitted. When it is, that value and every older
 * emitted value are dropped from `pending` (the parent has caught up to at least that point).
 *
 * @param pending emitted values not yet seen back as the `code` prop, oldest first (modified in place)
 * @param code    the incoming `code` prop
 * @returns true when `code` is an echo and must not be written back into the editor
 */
export function consumeEcho(pending: string[], code: string): boolean {
    const index: number = pending.lastIndexOf(code);
    if (index < 0) {
        return false;
    }
    pending.splice(0, index + 1);
    return true;
}

/**
 * A PatternFly {@link CodeEditor} that is safe to use as a controlled input (`code` + `onCodeChange`/`onChange`
 * feeding a parent state).
 *
 * PatternFly's CodeEditor keeps each keystroke in React state and passes it to Monaco as a controlled `value`;
 * it also copies the `code` prop into that state in an effect. When a render lags behind fast typing, a
 * commit carrying an older value runs after Monaco already holds newer text, and the older text is written
 * back into the editor, dropping what the user just typed. This wrapper makes Monaco uncontrolled, passes
 * the initial `code` once, ignores incoming values it emitted itself (even late ones), and applies genuinely
 * external changes (loading, inserting text, resets) directly to the Monaco model as an undoable edit.
 *
 * @param props the same props as CodeEditor
 * @returns the editor
 */
export function EditableCodeEditor(props: CodeEditorProps) {
    const { code = "", onChange, onCodeChange, onEditorDidMount, editorProps, ...rest } = props;
    const [initialCode] = useState<string>(code);
    const editorRef = useRef<Monaco.editor.IStandaloneCodeEditor | null>(null);
    const pendingRef = useRef<string[]>([]);
    const latestCodeRef = useRef<string>(code);

    const applyExternal = useCallback((value: string): void => {
        const editor = editorRef.current;
        const model = editor?.getModel();
        if (!editor || !model || model.getValue() === value) {
            return;
        }
        // The edit fires Monaco's change event, which reports the value back through onChange/onCodeChange.
        editor.pushUndoStop();
        model.pushEditOperations([], [{ range: model.getFullModelRange(), text: value }], () => null);
        editor.pushUndoStop();
    }, []);

    useEffect(() => {
        latestCodeRef.current = code;
        if (!consumeEcho(pendingRef.current, code)) {
            applyExternal(code);
        }
    }, [code, applyExternal]);

    const remember = (value: string): void => {
        const pending: string[] = pendingRef.current;
        if (pending[pending.length - 1] !== value) {
            pending.push(value);
            if (pending.length > MAX_PENDING) {
                pending.splice(0, pending.length - MAX_PENDING);
            }
        }
    };

    return (
        <CodeEditor
            {...rest}
            code={initialCode}
            // PatternFly merges editorProps after its own `value`. An undefined value makes the underlying
            // @monaco-editor/react editor uncontrolled, so PatternFly's per-keystroke value state (which can
            // commit after newer keystrokes) is never written back into Monaco.
            editorProps={{ ...editorProps, value: undefined, defaultValue: initialCode }}
            onChange={(value: string, event: Monaco.editor.IModelContentChangedEvent) => {
                remember(value);
                onChange?.(value, event);
            }}
            onCodeChange={(value: string) => {
                remember(value);
                onCodeChange?.(value);
            }}
            onEditorDidMount={(editor, monaco) => {
                editorRef.current = editor;
                // A value may have arrived (e.g. loaded from the server) before Monaco mounted.
                applyExternal(latestCodeRef.current);
                onEditorDidMount?.(editor, monaco);
            }}
        />
    );
}
