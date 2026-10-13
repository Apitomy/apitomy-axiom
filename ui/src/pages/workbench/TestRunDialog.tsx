import { useEffect, useState } from "react";
import {
    Alert, Button, Form, FormGroup, FormHelperText, FormSelect, FormSelectOption, HelperText, HelperTextItem,
    Modal, ModalBody, ModalFooter, ModalHeader, TextArea, TextInput,
} from "@patternfly/react-core";
import { fetchSubjects } from "../../config/api";
import { parseFieldValue, type TestField, testFields, testUnsupportedReason } from "./testRunLogic";

/** Props for {@link TestRunDialog}. */
export interface TestRunDialogProps {
    itemName: string;
    itemType: "action-type" | "tool";
    content: Record<string, unknown>;
    /** Texts typed in the previous run of this item. */
    initialValues: Record<string, string>;
    initialSubjectId?: number;
    /** Called with the parsed inputs, the raw texts and the chosen subject. */
    onRun(inputs: Record<string, unknown>, values: Record<string, string>, subjectId?: number): void;
    onClose(): void;
}

/**
 * Asks for sample inputs (or tool parameters) and an optional subject, then starts a test run.
 */
export function TestRunDialog(props: TestRunDialogProps) {
    const fields: TestField[] = testFields(props.itemType, props.content);
    const unsupported: string | null = testUnsupportedReason(props.itemType, props.content);
    const [values, setValues] = useState<Record<string, string>>(props.initialValues);
    const [errors, setErrors] = useState<Record<string, string>>({});
    const [subjectId, setSubjectId] = useState<string>(props.initialSubjectId ? String(props.initialSubjectId) : "");
    const [subjects, setSubjects] = useState<{ id: number; name: string }[]>([]);
    const noun: string = props.itemType === "tool" ? "parameters" : "inputs";

    useEffect(() => {
        fetchSubjects(1, 100).then((r) => setSubjects(r.items.map((s) => ({ id: s.id, name: s.name }))))
            .catch(console.error);
    }, []);

    const run = () => {
        const nextErrors: Record<string, string> = {};
        const inputs: Record<string, unknown> = {};
        for (const field of fields) {
            const parsed = parseFieldValue(field, values[field.name] ?? "");
            if (parsed.error) {
                nextErrors[field.name] = parsed.error;
            } else if (parsed.value !== undefined) {
                inputs[field.name] = parsed.value;
            } else if (field.required) {
                nextErrors[field.name] = "Required.";
            }
        }
        setErrors(nextErrors);
        if (Object.keys(nextErrors).length === 0) {
            props.onRun(inputs, values, subjectId ? Number(subjectId) : undefined);
        }
    };

    return (
        <Modal isOpen variant="medium" onClose={props.onClose} aria-labelledby="test-run-title"
            elementToFocus={!unsupported && fields.length > 0 ? `#test-field-${fields[0].name}` : undefined}>
            <ModalHeader title={`Test ${props.itemName}`} labelId="test-run-title" />
            <ModalBody>
                {unsupported ? <Alert isInline variant="info" title={unsupported} /> : (
                    <Form onSubmit={(e) => { e.preventDefault(); run(); }}>
                        {fields.length === 0 && <p className="axiom-text-subtle">No {noun} are declared.</p>}
                        {fields.map((field) => {
                            const multiline: boolean = field.type === "object" || field.type === "array";
                            const id: string = `test-field-${field.name}`;
                            const onChange = (_e: unknown, v: string) => setValues((prev) => ({ ...prev, [field.name]: v }));
                            return (
                                <FormGroup key={field.name} label={field.name} isRequired={field.required} fieldId={id}>
                                    {multiline
                                        ? <TextArea id={id} aria-label={field.name} value={values[field.name] ?? ""}
                                            onChange={onChange} rows={4} resizeOrientation="vertical" />
                                        : <TextInput id={id} aria-label={field.name} value={values[field.name] ?? ""}
                                            onChange={onChange} />}
                                    <FormHelperText>
                                        <HelperText>
                                            {errors[field.name]
                                                ? <HelperTextItem variant="error">{errors[field.name]}</HelperTextItem>
                                                : <HelperTextItem>{field.type}{field.description ? ` — ${field.description}` : ""}</HelperTextItem>}
                                        </HelperText>
                                    </FormHelperText>
                                </FormGroup>
                            );
                        })}
                        <FormGroup label="Subject" fieldId="test-subject">
                            <FormSelect id="test-subject" value={subjectId} onChange={(_e, v) => setSubjectId(v)}>
                                <FormSelectOption value="" label="None — run in a temporary directory" />
                                {subjects.map((s) => <FormSelectOption key={s.id} value={String(s.id)} label={s.name} />)}
                            </FormSelect>
                        </FormGroup>
                    </Form>
                )}
            </ModalBody>
            <ModalFooter>
                {!unsupported && <Button variant="primary" onClick={run}>Run</Button>}
                <Button variant="link" onClick={props.onClose}>{unsupported ? "Close" : "Cancel"}</Button>
            </ModalFooter>
        </Modal>
    );
}
