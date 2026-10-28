import { describe, expect, it, vi } from "vitest";
import { act, render } from "@testing-library/react";
import { EditableCodeEditor, consumeEcho } from "./EditableCodeEditor";

/** Captured props of the mocked PatternFly CodeEditor. */
let editorProps: Record<string, any> = {};

/** A minimal fake Monaco editor/model pair. */
function fakeEditor(initial: string) {
    let value = initial;
    const model = {
        getValue: () => value,
        getFullModelRange: () => ({}),
        pushEditOperations: vi.fn((_sel: unknown, ops: { text: string }[]) => { value = ops[0].text; return null; }),
    };
    return { editor: { getModel: () => model, getValue: () => value, pushUndoStop: vi.fn() }, model,
        type: (v: string) => { value = v; editorProps.onCodeChange?.(v); } };
}

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: (props: Record<string, any>) => { editorProps = props; return <div data-testid="editor" />; },
}));

describe("consumeEcho", () => {
    it("recognises late echoes of emitted values and drops older ones", () => {
        const pending: string[] = ["e", "ev", "eve"];
        expect(consumeEcho(pending, "ev")).toBe(true);
        expect(pending).toEqual(["eve"]);
        expect(consumeEcho(pending, "other")).toBe(false);
        expect(pending).toEqual(["eve"]);
    });
});

describe("EditableCodeEditor", () => {
    it("makes Monaco uncontrolled so PatternFly's lagging value state is never written back", () => {
        render(<EditableCodeEditor code="initial" onCodeChange={vi.fn()} editorProps={{ path: "x" }} />);
        expect(Object.prototype.hasOwnProperty.call(editorProps.editorProps, "value")).toBe(true);
        expect(editorProps.editorProps.value).toBeUndefined();
        expect(editorProps.editorProps.defaultValue).toBe("initial");
        expect(editorProps.editorProps.path).toBe("x");
    });

    it("does not push a stale parent value back over newer keystrokes", () => {
        const onCodeChange = vi.fn();
        const { rerender } = render(<EditableCodeEditor code="" onCodeChange={onCodeChange} />);
        const fake = fakeEditor("");
        act(() => editorProps.onEditorDidMount(fake.editor, {}));

        fake.type("e");
        fake.type("ev");
        // The parent re-renders late with the first keystroke's value.
        rerender(<EditableCodeEditor code="e" onCodeChange={onCodeChange} />);
        rerender(<EditableCodeEditor code="ev" onCodeChange={onCodeChange} />);

        expect(fake.model.pushEditOperations).not.toHaveBeenCalled();
        expect(fake.editor.getValue()).toBe("ev");
        expect(onCodeChange).toHaveBeenLastCalledWith("ev");
        expect(editorProps.code).toBe("");
    });

    it("applies an external change to the editor", () => {
        const { rerender } = render(<EditableCodeEditor code="abc" onCodeChange={vi.fn()} />);
        const fake = fakeEditor("abc");
        act(() => editorProps.onEditorDidMount(fake.editor, {}));

        rerender(<EditableCodeEditor code="abc event.type" onCodeChange={vi.fn()} />);

        expect(fake.editor.getValue()).toBe("abc event.type");
    });

    it("applies an external reset to a value the user typed past", () => {
        const { rerender } = render(<EditableCodeEditor code="abc" onCodeChange={vi.fn()} />);
        const fake = fakeEditor("abc");
        act(() => editorProps.onEditorDidMount(fake.editor, {}));
        fake.type("abcd");
        rerender(<EditableCodeEditor code="abcd" onCodeChange={vi.fn()} />);

        rerender(<EditableCodeEditor code="abc" onCodeChange={vi.fn()} />);

        expect(fake.editor.getValue()).toBe("abc");
    });

    it("applies a value that arrived before the editor mounted", () => {
        const { rerender } = render(<EditableCodeEditor code="" onCodeChange={vi.fn()} />);
        rerender(<EditableCodeEditor code="loaded" onCodeChange={vi.fn()} />);
        const fake = fakeEditor("");
        act(() => editorProps.onEditorDidMount(fake.editor, {}));
        expect(fake.editor.getValue()).toBe("loaded");
    });

    it("forwards onChange and the caller's onEditorDidMount", () => {
        const onChange = vi.fn();
        const onMount = vi.fn();
        render(<EditableCodeEditor code="" onChange={onChange} onEditorDidMount={onMount} />);
        const fake = fakeEditor("");
        act(() => editorProps.onEditorDidMount(fake.editor, { m: 1 }));
        editorProps.onChange("x", { e: 1 });
        expect(onMount).toHaveBeenCalledWith(fake.editor, { m: 1 });
        expect(onChange).toHaveBeenCalledWith("x", { e: 1 });
    });
});
