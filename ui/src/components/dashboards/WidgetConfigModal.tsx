import { useState, useEffect } from "react";
import {
    Button,
    Form,
    FormHelperText,
    HelperText,
    HelperTextItem,
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
import { type ConfigField, toStringArray } from "./widget-registry";
import { EntitySelectField } from "./EntitySelectField";
import { MultiSelectField } from "./MultiSelectField";

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
                            {field.type === "number" && (() => {
                                const minVal = field.min ?? 0;
                                const maxVal = field.max ?? Number.MAX_SAFE_INTEGER;
                                const current = Number(draft[field.key] ?? field.default);
                                return (
                                    <NumberInput
                                        id={field.key}
                                        value={current}
                                        min={minVal}
                                        max={maxVal}
                                        onMinus={() => updateField(field.key,
                                            Math.max(minVal, current - 1))}
                                        onPlus={() => updateField(field.key,
                                            Math.min(maxVal, current + 1))}
                                        onChange={(event) => {
                                            const val = Number((event.target as HTMLInputElement).value);
                                            if (!isNaN(val)) updateField(field.key, val);
                                        }}
                                    />
                                );
                            })()}
                            {field.type === "toggle" && (
                                <Switch
                                    id={field.key}
                                    aria-label={field.label}
                                    isChecked={Boolean(draft[field.key] ?? field.default)}
                                    onChange={(_e, checked) => updateField(field.key, checked)}
                                />
                            )}
                            {field.type === "multiselect" && (
                                <MultiSelectField
                                    id={field.key}
                                    label={field.label}
                                    options={field.options ?? []}
                                    value={toStringArray(draft[field.key] ?? field.default)}
                                    onChange={(val) => updateField(field.key, val)}
                                />
                            )}
                            {(field.type === "subject" || field.type === "workflow"
                                || field.type === "eventSource" || field.type === "subscription") && (
                                <EntitySelectField
                                    id={field.key}
                                    kind={field.type}
                                    label={field.label}
                                    value={draft[field.key] ?? field.default}
                                    onChange={(val) => updateField(field.key, val)}
                                />
                            )}
                            {field.helperText && (
                                <FormHelperText>
                                    <HelperText><HelperTextItem>{field.helperText}</HelperTextItem></HelperText>
                                </FormHelperText>
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
