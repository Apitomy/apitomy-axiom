import { useEffect, useState } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    FormGroup,
    FormSelect,
    FormSelectOption,
    TextInput,
} from "@patternfly/react-core";
import { fetchSecrets } from "../config/api";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";

/**
 * Editable key-value table for environment variables. A process gets only these variables: secrets are never
 * injected automatically, so each secret it needs is declared here as {@code NAME=${secret:NAME}} (the
 * "Add secret" picker does this).
 *
 * Used by the action type editor, session templates and workbench tool test runs.
 */
export function EnvironmentTab({ envVars, onChange, description }: {
    envVars: Record<string, string>;
    onChange: (updated: Record<string, string>) => void;
    /** Replaces the default explanation shown above the table. */
    description?: string;
}) {
    const [newName, setNewName] = useState("");
    const [newValue, setNewValue] = useState("");
    const [secretNames, setSecretNames] = useState<string[]>([]);

    useEffect(() => {
        fetchSecrets().then((secrets) => setSecretNames(secrets.map((s) => s.name).sort()))
            .catch(console.error);
    }, []);

    const addSecret = (name: string) => {
        if (name && !(name in envVars)) {
            onChange({ ...envVars, [name]: `\${secret:${name}}` });
        }
    };
    const undeclaredSecrets: string[] = secretNames.filter((name) => !(name in envVars));

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
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                {description ?? "Environment variables for the process. Secrets are not injected automatically: "
                    + "the process gets only the variables listed here."} Reference a secret
                as <code>{"${secret:SECRET_NAME}"}</code>, or use <strong>Add secret</strong>.
            </p>

            <FormGroup label="Add secret" fieldId="env-add-secret" style={{ marginBottom: "16px" }}>
                <FormSelect id="env-add-secret" aria-label="Add secret" value=""
                    isDisabled={undeclaredSecrets.length === 0}
                    onChange={(_e, v) => addSecret(v)}>
                    <FormSelectOption value="" label="Add secret…" />
                    {undeclaredSecrets.map((name) => <FormSelectOption key={name} value={name} label={name} />)}
                </FormSelect>
            </FormGroup>

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
                        No environment variables: the process gets no secrets.
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
