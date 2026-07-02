import { useCallback } from "react";
import {
    Form,
    FormGroup,
    FormHelperText,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    Switch,
    TextArea,
    TextInput,
} from "@patternfly/react-core";
import type { OutputSchema, OutputSchemaField } from "../config/api";

export interface DynamicFormRendererProps {
    schema: OutputSchema | undefined;
    values: Record<string, unknown>;
    onChange: (values: Record<string, unknown>) => void;
    errors?: Record<string, string>;
}

/**
 * Renders PatternFly form fields dynamically from an OutputSchema.
 * Falls back to a single TextArea when no schema is provided.
 */
export function DynamicFormRenderer({ schema, values, onChange, errors }: DynamicFormRendererProps) {
    const updateField = useCallback((name: string, value: unknown) => {
        onChange({ ...values, [name]: value });
    }, [values, onChange]);

    if (!schema || !schema.fields || schema.fields.length === 0) {
        return (
            <Form>
                <FormGroup label="Response" isRequired fieldId="freeform-response">
                    <TextArea
                        id="freeform-response"
                        value={(values["response"] as string) || ""}
                        onChange={(_e, val) => updateField("response", val)}
                        rows={6}
                        resizeOrientation="vertical"
                    />
                </FormGroup>
            </Form>
        );
    }

    return (
        <Form>
            {schema.fields.map((field) => (
                <DynamicField
                    key={field.name}
                    field={field}
                    value={values[field.name]}
                    onChange={(val) => updateField(field.name, val)}
                    error={errors?.[field.name]}
                />
            ))}
        </Form>
    );
}

interface DynamicFieldProps {
    field: OutputSchemaField;
    value: unknown;
    onChange: (value: unknown) => void;
    error?: string;
}

function DynamicField({ field, value, onChange, error }: DynamicFieldProps) {
    const fieldId = `field-${field.name}`;
    const validated = error ? "error" as const : "default" as const;

    return (
        <FormGroup
            label={field.label}
            isRequired={field.required}
            fieldId={fieldId}
        >
            {renderInput(field, fieldId, value, onChange, validated)}
            {(field.description || error) && (
                <FormHelperText>
                    <HelperText>
                        {error ? (
                            <HelperTextItem variant="error">{error}</HelperTextItem>
                        ) : (
                            <HelperTextItem>{field.description}</HelperTextItem>
                        )}
                    </HelperText>
                </FormHelperText>
            )}
        </FormGroup>
    );
}

function renderInput(
    field: OutputSchemaField,
    fieldId: string,
    value: unknown,
    onChange: (value: unknown) => void,
    validated: "error" | "default",
) {
    switch (field.type) {
        case "text":
            return (
                <TextInput
                    id={fieldId}
                    type="text"
                    value={(value as string) ?? (field.defaultValue as string) ?? ""}
                    onChange={(_e, val) => onChange(val)}
                    validated={validated}
                />
            );
        case "textarea":
            return (
                <TextArea
                    id={fieldId}
                    value={(value as string) ?? (field.defaultValue as string) ?? ""}
                    onChange={(_e, val) => onChange(val)}
                    rows={4}
                    resizeOrientation="vertical"
                    validated={validated}
                />
            );
        case "boolean":
            return (
                <Switch
                    id={fieldId}
                    isChecked={(value as boolean) ?? (field.defaultValue as boolean) ?? false}
                    onChange={(_e, checked) => onChange(checked)}
                    label="Yes"
                />
            );
        case "select":
            return (
                <FormSelect
                    id={fieldId}
                    value={(value as string) ?? (field.defaultValue as string) ?? ""}
                    onChange={(_e, val) => onChange(val)}
                    validated={validated}
                >
                    <FormSelectOption key="" value="" label="Select..." isDisabled />
                    {(field.options ?? []).map((opt) => (
                        <FormSelectOption
                            key={opt.value}
                            value={opt.value}
                            label={opt.label}
                        />
                    ))}
                </FormSelect>
            );
        case "number":
            return (
                <TextInput
                    id={fieldId}
                    type="number"
                    value={value != null ? Number(value) : (field.defaultValue as number) ?? ""}
                    onChange={(_e, val) => onChange(val === "" ? null : Number(val))}
                    validated={validated}
                />
            );
        default:
            return (
                <TextInput
                    id={fieldId}
                    type="text"
                    value={(value as string) ?? ""}
                    onChange={(_e, val) => onChange(val)}
                />
            );
    }
}
