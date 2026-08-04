import { useState, useEffect } from "react";
import {
    Button,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    NumberInput,
    Switch,
} from "@patternfly/react-core";
import type { ConfigField } from "./widget-registry";

interface WidgetConfigModalProps {
    isOpen: boolean;
    widgetName: string;
    configSchema: ConfigField[];
    config: Record<string, unknown>;
    onSave: (config: Record<string, unknown>) => void;
    onCancel: () => void;
}

/**
 * Renders a settings form for a widget based on its configSchema definition.
 */
export function WidgetConfigModal({
    isOpen, widgetName, configSchema, config, onSave, onCancel,
}: WidgetConfigModalProps) {
    const [draft, setDraft] = useState<Record<string, unknown>>({ ...config });

    useEffect(() => {
        if (isOpen) {
            setDraft({ ...config });
        }
    }, [isOpen, config]);

    const updateField = (key: string, value: unknown) => {
        setDraft(prev => ({ ...prev, [key]: value }));
    };

    return (
        <Modal isOpen={isOpen} onClose={onCancel} variant="small">
            <ModalHeader title={`${widgetName} Settings`} />
            <ModalBody>
                <Form>
                    {configSchema.map(field => (
                        <FormGroup key={field.key} label={field.label} fieldId={field.key}>
                            {field.type === "select" && (
                                <FormSelect
                                    id={field.key}
                                    value={String(draft[field.key] ?? field.default)}
                                    onChange={(_e, val) => updateField(field.key, val)}
                                >
                                    {field.options?.map(opt => (
                                        <FormSelectOption key={opt.value}
                                                          value={opt.value}
                                                          label={opt.label} />
                                    ))}
                                </FormSelect>
                            )}
                            {field.type === "number" && (
                                <NumberInput
                                    id={field.key}
                                    value={Number(draft[field.key] ?? field.default)}
                                    min={1}
                                    max={100}
                                    onMinus={() => updateField(field.key,
                                        Math.max(1, Number(draft[field.key] ?? field.default) - 1))}
                                    onPlus={() => updateField(field.key,
                                        Number(draft[field.key] ?? field.default) + 1)}
                                    onChange={(event) => {
                                        const val = Number((event.target as HTMLInputElement).value);
                                        if (!isNaN(val)) updateField(field.key, val);
                                    }}
                                />
                            )}
                            {field.type === "toggle" && (
                                <Switch
                                    id={field.key}
                                    isChecked={Boolean(draft[field.key] ?? field.default)}
                                    onChange={(_e, checked) => updateField(field.key, checked)}
                                />
                            )}
                            {field.type === "multiselect" && (
                                <FormSelect
                                    id={field.key}
                                    value={String(draft[field.key] ?? field.default)}
                                    onChange={(_e, val) => updateField(field.key, val)}
                                >
                                    <FormSelectOption value="" label="All" />
                                    {field.options?.map(opt => (
                                        <FormSelectOption key={opt.value}
                                                          value={opt.value}
                                                          label={opt.label} />
                                    ))}
                                </FormSelect>
                            )}
                        </FormGroup>
                    ))}
                </Form>
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={() => onSave(draft)}>Save</Button>
                <Button variant="link" onClick={onCancel}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
