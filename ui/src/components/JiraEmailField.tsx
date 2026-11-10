import { useEffect, useState } from "react";
import {
    Flex,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    Radio,
    TextInput,
} from "@patternfly/react-core";
import { parseSecretValue } from "./KeyValueSecretEditor";

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/**
 * Returns whether a Jira email setting is acceptable: empty, a plain email address, or a
 * {@code ${secret:NAME}} reference.
 */
export function isValidJiraEmail(value: string): boolean {
    const trimmed = value.trim();
    if (trimmed === "") return true;
    const parsed = parseSecretValue(trimmed);
    if (parsed) return parsed.prefix === "";
    return EMAIL.test(trimmed);
}

/** Props of {@link JiraEmailField}. */
export interface JiraEmailFieldProps {
    /** The stored value: an email address, {@code ${secret:NAME}}, or "" when not set. */
    value: string;
    /** Names of the existing secrets. */
    secrets: string[];
    /** Called with the new stored value. */
    onChange: (value: string) => void;
}

/**
 * Editor for a Jira event source's account email: typed as plain text or chosen from the existing secrets.
 * A chosen secret is stored as {@code ${secret:NAME}} without the user typing that syntax.
 */
export function JiraEmailField({ value, secrets, onChange }: JiraEmailFieldProps) {
    const parsed = parseSecretValue(value);
    const [mode, setMode] = useState<"plain" | "secret">(parsed ? "secret" : "plain");

    // A value loaded after the first render (edit page) decides the mode.
    useEffect(() => {
        if (parseSecretValue(value)) setMode("secret");
    }, [value]);

    const switchMode = (next: "plain" | "secret") => {
        if (next === mode) return;
        setMode(next);
        onChange("");
    };

    const selected = parsed?.secret ?? "";
    const options = selected && !secrets.includes(selected) ? [selected, ...secrets] : secrets;

    return (
        <div>
            <Flex style={{ gap: "16px", marginBottom: "8px" }}>
                <Radio id="jiraEmail-plain" name="jiraEmailMode" label="Plain text"
                    isChecked={mode === "plain"} onChange={() => switchMode("plain")} />
                <Radio id="jiraEmail-secret" name="jiraEmailMode" label="Secret"
                    isChecked={mode === "secret"} onChange={() => switchMode("secret")} />
            </Flex>
            {mode === "plain" ? (
                <TextInput id="jiraEmail" aria-label="Jira email address" type="email"
                    value={parsed ? "" : value} placeholder="you@example.com"
                    validated={isValidJiraEmail(value) ? "default" : "error"}
                    onChange={(_e, v) => onChange(v)} />
            ) : (
                <FormSelect id="jiraEmail" aria-label="Jira email secret" value={selected}
                    onChange={(_e, v) => onChange(v ? `\${secret:${v}}` : "")}>
                    <FormSelectOption value="" label={secrets.length ? "Choose a secret…" : "No secrets defined"} />
                    {options.map((s) => (
                        <FormSelectOption key={s} value={s}
                            label={secrets.includes(s) ? s : `${s} (does not exist)`} />
                    ))}
                </FormSelect>
            )}
            <HelperText>
                {isValidJiraEmail(value) ? (
                    <HelperTextItem>
                        The Jira account email, combined with the API token from the authentication secret.
                    </HelperTextItem>
                ) : (
                    <HelperTextItem variant="error">Enter a valid email address</HelperTextItem>
                )}
            </HelperText>
        </div>
    );
}
