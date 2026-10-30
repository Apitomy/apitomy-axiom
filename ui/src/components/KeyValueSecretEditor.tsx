import { useEffect, useState } from "react";
import {
    Button,
    Flex,
    FlexItem,
    FormSelect,
    FormSelectOption,
    Radio,
    TextInput,
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import EyeIcon from "@patternfly/react-icons/dist/esm/icons/eye-icon";
import EyeSlashIcon from "@patternfly/react-icons/dist/esm/icons/eye-slash-icon";
import { fetchSecrets } from "../config/api";

const SECRET_VALUE = /^(.*)\$\{secret:([^}]+)\}$/s;

/**
 * Splits a value of the form {@code <prefix>${secret:NAME}} (e.g. {@code Bearer ${secret:TOKEN}}).
 *
 * @returns the prefix and secret name, or null for plain values
 */
export function parseSecretValue(value: string): { prefix: string; secret: string } | null {
    const match = SECRET_VALUE.exec(value);
    return match ? { prefix: match[1], secret: match[2] } : null;
}

function secretValue(prefix: string, secret: string): string {
    return `${prefix}\${secret:${secret}}`;
}

/** Props of {@link KeyValueSecretEditor}. */
export interface KeyValueSecretEditorProps {
    /** Accessible label (e.g. "Environment", "Headers"). */
    label: string;
    /** Placeholder for the key input (e.g. "Name", "Header"). */
    keyPlaceholder: string;
    /** The entries. */
    values: Record<string, string>;
    /** Called with the new entries. */
    onChange: (values: Record<string, string>) => void;
}

/** Editor for one value: plain (masked, revealable) or a secret reference with an optional prefix. */
function ValueEditor({ id, value, secrets, onChange }: {
    id: string; value: string; secrets: string[]; onChange: (value: string) => void;
}) {
    const [revealed, setRevealed] = useState(false);
    const parsed = parseSecretValue(value);
    if (parsed) {
        const options = secrets.includes(parsed.secret) ? secrets : [parsed.secret, ...secrets];
        return (
            <Flex style={{ gap: "4px" }} flexWrap={{ default: "nowrap" }}>
                <FlexItem style={{ flex: 1 }}>
                    <TextInput aria-label={`Prefix for ${id}`} value={parsed.prefix} placeholder="Prefix (optional)"
                        onChange={(_e, v) => onChange(secretValue(v, parsed.secret))} />
                </FlexItem>
                <FlexItem style={{ flex: 2 }}>
                    <FormSelect aria-label={`Secret for ${id}`} value={parsed.secret}
                        onChange={(_e, v) => onChange(secretValue(parsed.prefix, v))}>
                        {options.map((s) => (
                            <FormSelectOption key={s} value={s}
                                label={secrets.includes(s) ? s : `${s} (does not exist)`} />
                        ))}
                    </FormSelect>
                </FlexItem>
            </Flex>
        );
    }
    return (
        <Flex style={{ gap: "4px" }} flexWrap={{ default: "nowrap" }}>
            <FlexItem grow={{ default: "grow" }}>
                <TextInput aria-label={`Value for ${id}`} value={value} type={revealed ? "text" : "password"}
                    onChange={(_e, v) => onChange(v)} />
            </FlexItem>
            <FlexItem>
                <Button variant="plain" size="sm" aria-label={revealed ? `Hide ${id}` : `Show ${id}`}
                    onClick={() => setRevealed(!revealed)}>
                    {revealed ? <EyeSlashIcon /> : <EyeIcon />}
                </Button>
            </FlexItem>
        </Flex>
    );
}

/**
 * Key/value editor whose values are either plain text (masked) or references to Axiom secrets
 * ({@code ${secret:NAME}}, optionally with a prefix such as {@code Bearer }). Secret references are resolved
 * only when a session or task starts.
 */
export function KeyValueSecretEditor({ label, keyPlaceholder, values, onChange }: KeyValueSecretEditorProps) {
    const [secrets, setSecrets] = useState<string[]>([]);
    const [newKey, setNewKey] = useState("");
    const [newMode, setNewMode] = useState<"plain" | "secret">("plain");
    const [newValue, setNewValue] = useState("");
    const [newSecret, setNewSecret] = useState("");

    useEffect(() => {
        fetchSecrets().then((list) => setSecrets(list.map((s) => s.name).sort())).catch(() => setSecrets([]));
    }, []);

    const key = newKey.trim();
    const canAdd = key !== "" && !(key in values) && (newMode === "plain" || newSecret !== "");
    const add = () => {
        if (!canAdd) return;
        onChange({ ...values, [key]: newMode === "secret" ? secretValue("", newSecret) : newValue });
        setNewKey("");
        setNewValue("");
        setNewSecret("");
    };
    const remove = (k: string) => {
        const next = { ...values };
        delete next[k];
        onChange(next);
    };
    const groupName = `kv-mode-${label}`;

    return (
        <div>
            {Object.entries(values).map(([k, v]) => (
                <Flex key={k} data-testid={`kv-row-${k}`} alignItems={{ default: "alignItemsCenter" }}
                    flexWrap={{ default: "nowrap" }}
                    style={{ marginBottom: "4px", gap: "8px", padding: "4px 8px",
                        backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                        borderRadius: "4px" }}>
                    <FlexItem style={{ minWidth: "180px" }}><code>{k}</code></FlexItem>
                    <FlexItem grow={{ default: "grow" }}>
                        <ValueEditor id={k} value={v} secrets={secrets}
                            onChange={(value) => onChange({ ...values, [k]: value })} />
                    </FlexItem>
                    <FlexItem>
                        <Button variant="plain" size="sm" aria-label={`Use a ${parseSecretValue(v) ? "plain value" : "secret"} for ${k}`}
                            onClick={() => onChange({ ...values, [k]: parseSecretValue(v) ? "" : secretValue("", secrets[0] ?? "") })}>
                            {parseSecretValue(v) ? "Use plain value" : "Use secret"}
                        </Button>
                        <Button variant="plain" size="sm" aria-label={`Remove ${k}`} onClick={() => remove(k)}>
                            <TimesIcon />
                        </Button>
                    </FlexItem>
                </Flex>
            ))}
            <Flex alignItems={{ default: "alignItemsCenter" }} flexWrap={{ default: "nowrap" }}
                style={{ gap: "8px", marginTop: "8px" }}>
                <FlexItem style={{ minWidth: "180px" }}>
                    <TextInput aria-label={`New ${label} name`} placeholder={keyPlaceholder} value={newKey}
                        onChange={(_e, v) => setNewKey(v)} />
                </FlexItem>
                <FlexItem>
                    <Flex style={{ gap: "8px" }} flexWrap={{ default: "nowrap" }}>
                        <Radio id={`${groupName}-plain`} name={groupName} label="Plain"
                            isChecked={newMode === "plain"} onChange={() => setNewMode("plain")} />
                        <Radio id={`${groupName}-secret`} name={groupName} label="Secret"
                            isChecked={newMode === "secret"} onChange={() => setNewMode("secret")} />
                    </Flex>
                </FlexItem>
                <FlexItem grow={{ default: "grow" }}>
                    {newMode === "plain" ? (
                        <TextInput aria-label={`New ${label} value`} placeholder="Value" value={newValue}
                            onChange={(_e, v) => setNewValue(v)}
                            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} />
                    ) : (
                        <FormSelect aria-label={`New ${label} secret`} value={newSecret}
                            onChange={(_e, v) => setNewSecret(v)}>
                            <FormSelectOption value="" label={secrets.length ? "Choose a secret…" : "No secrets defined"} />
                            {secrets.map((s) => <FormSelectOption key={s} value={s} label={s} />)}
                        </FormSelect>
                    )}
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlusCircleIcon />} onClick={add} isDisabled={!canAdd}>
                        Add
                    </Button>
                </FlexItem>
            </Flex>
        </div>
    );
}
