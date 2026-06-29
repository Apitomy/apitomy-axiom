import { useState } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    FormGroup,
    TextInput,
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";

/**
 * Editable key-value table for environment variables.
 *
 * Used by ActionTypeDetailPage and ReportDefinitionDetailPage.
 */
export function EnvironmentTab({ envVars, onChange }: {
    envVars: Record<string, string>;
    onChange: (updated: Record<string, string>) => void;
}) {
    const [newName, setNewName] = useState("");
    const [newValue, setNewValue] = useState("");

    const entries = Object.entries(envVars);

    const handleAdd = () => {
        const trimmed = newName.trim();
        if (!trimmed || trimmed in envVars) return;
        onChange({ ...envVars, [trimmed]: newValue });
        setNewName("");
        setNewValue("");
    };

    const handleRemove = (key: string) => {
        const updated = { ...envVars };
        delete updated[key];
        onChange(updated);
    };

    const handleValueChange = (key: string, value: string) => {
        onChange({ ...envVars, [key]: value });
    };

    return (
        <div style={{ maxWidth: "700px" }}>
            <p style={{ color: "#6a6e73", marginBottom: "16px" }}>
                Custom environment variables injected into the subprocess. When configured,
                these replace the default all-secrets injection. Reference an encrypted secret
                using <code>{"${secret:SECRET_NAME}"}</code> syntax.
            </p>

            <Flex alignItems={{ default: "alignItemsFlexEnd" }}
                style={{ marginBottom: "16px", gap: "8px" }}>
                <FlexItem style={{ flex: 1 }}>
                    <FormGroup label="Name" fieldId="env-new-name">
                        <TextInput id="env-new-name" value={newName}
                            onChange={(_e, v) => setNewName(v)}
                            placeholder="e.g. GH_TOKEN"
                            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAdd(); } }}
                        />
                    </FormGroup>
                </FlexItem>
                <FlexItem style={{ flex: 2 }}>
                    <FormGroup label="Value" fieldId="env-new-value">
                        <TextInput id="env-new-value" value={newValue}
                            onChange={(_e, v) => setNewValue(v)}
                            placeholder="e.g. ${secret:GH_TOKEN}"
                            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAdd(); } }}
                        />
                    </FormGroup>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlusCircleIcon />}
                        onClick={handleAdd}
                        isDisabled={!newName.trim() || newName.trim() in envVars}
                        style={{ marginBottom: "1px" }}>
                        Add
                    </Button>
                </FlexItem>
            </Flex>

            {entries.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        No custom environment configured. All secrets will be injected automatically.
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                    {entries.map(([key, value]) => (
                        <Flex key={key} alignItems={{ default: "alignItemsCenter" }}
                            style={{
                                padding: "8px 12px",
                                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                            }}>
                            <FlexItem style={{ minWidth: "160px" }}>
                                <code style={{ fontSize: "13px", fontWeight: 600 }}>{key}</code>
                            </FlexItem>
                            <FlexItem grow={{ default: "grow" }}>
                                <TextInput value={value}
                                    onChange={(_e, v) => handleValueChange(key, v)}
                                    aria-label={`Value for ${key}`}
                                    style={{ fontSize: "13px" }}
                                />
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" size="sm"
                                    onClick={() => handleRemove(key)}
                                    aria-label={`Remove ${key}`}>
                                    <TimesIcon />
                                </Button>
                            </FlexItem>
                        </Flex>
                    ))}
                </div>
            )}
        </div>
    );
}
