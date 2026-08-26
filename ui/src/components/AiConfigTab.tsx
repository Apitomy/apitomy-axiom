import {
    FormGroup,
    FormSelect,
    FormSelectOption,
    FormSelectOptionGroup,
    HelperText,
    HelperTextItem,
    TextInput,
} from "@patternfly/react-core";

export interface AiConfigValues {
    engine?: string;
    model?: string;
    maxSteps?: number;
    maxBudgetUsd?: number;
    timeoutSeconds?: number;
}

const ENGINE_LABELS: Record<string, string> = {
    "claude-code": "Claude Code",
    "opencode": "OpenCode",
    "copilot": "GitHub Copilot CLI",
};

/**
 * Shared AI configuration form fields used by Action Types,
 * Scheduled Jobs, and Report Definitions.
 */
export function AiConfigTab({ values, onChange, availableEngines, availableModels }: {
    values: AiConfigValues;
    onChange: (updates: Partial<AiConfigValues>) => void;
    availableEngines: string[];
    availableModels: string[];
}) {
    return (
        <>
            {availableEngines.length > 1 && (
                <FormGroup label="Engine" fieldId="engine">
                    <HelperText>
                        <HelperTextItem>
                            AI engine to use. Select &lsquo;Global default&rsquo; to use
                            the system-wide setting.
                        </HelperTextItem>
                    </HelperText>
                    <FormSelect
                        id="engine"
                        value={values.engine || ""}
                        onChange={(_e, v) => onChange({ engine: v || undefined })}
                    >
                        <FormSelectOption value="" label="Global default" />
                        {availableEngines.map((e) => (
                            <FormSelectOption
                                key={e}
                                value={e}
                                label={ENGINE_LABELS[e] || e}
                            />
                        ))}
                    </FormSelect>
                </FormGroup>
            )}

            <FormGroup label="Model" fieldId="model">
                <HelperText>
                    <HelperTextItem>
                        AI model to use. Select &lsquo;Global default&rsquo; to use
                        the system-wide setting.
                    </HelperTextItem>
                </HelperText>
                <FormSelect
                    id="model"
                    value={values.model || ""}
                    onChange={(_e, v) => onChange({ model: v || undefined })}
                >
                    <FormSelectOption value="" label="Global default" />
                    {renderModelOptions(availableModels)}
                </FormSelect>
            </FormGroup>

            <FormGroup label="Max Steps" fieldId="maxSteps">
                <HelperText>
                    <HelperTextItem>
                        Maximum number of agent steps/turns. Leave empty to use the
                        global default.
                    </HelperTextItem>
                </HelperText>
                <TextInput
                    id="maxSteps"
                    type="number"
                    value={values.maxSteps ?? ""}
                    onChange={(_e, v) =>
                        onChange({ maxSteps: v === "" ? undefined : Number(v) })
                    }
                    placeholder="Global default"
                />
            </FormGroup>

            <FormGroup label="Max Budget (USD)" fieldId="maxBudgetUsd">
                <HelperText>
                    <HelperTextItem>
                        Maximum budget in USD per execution. Leave empty to use the
                        global default.
                    </HelperTextItem>
                </HelperText>
                <TextInput
                    id="maxBudgetUsd"
                    type="number"
                    value={values.maxBudgetUsd ?? ""}
                    onChange={(_e, v) =>
                        onChange({ maxBudgetUsd: v === "" ? undefined : Number(v) })
                    }
                    placeholder="Global default"
                />
            </FormGroup>

            <FormGroup label="Timeout (seconds)" fieldId="timeoutSeconds">
                <HelperText>
                    <HelperTextItem>
                        Timeout in seconds for agent execution. Leave empty to use the
                        global default.
                    </HelperTextItem>
                </HelperText>
                <TextInput
                    id="timeoutSeconds"
                    type="number"
                    value={values.timeoutSeconds ?? ""}
                    onChange={(_e, v) =>
                        onChange({
                            timeoutSeconds: v === "" ? undefined : Number(v),
                        })
                    }
                    placeholder="Global default"
                />
            </FormGroup>
        </>
    );
}

function renderModelOptions(models: string[]) {
    const hasProviders = models.some((m) => m.includes("/"));
    if (hasProviders) {
        const groups: Record<string, string[]> = {};
        for (const m of models) {
            if (m.includes("/")) {
                const [provider] = m.split("/", 2);
                const key =
                    provider.charAt(0).toUpperCase() + provider.slice(1);
                if (!groups[key]) groups[key] = [];
                groups[key].push(m);
            } else {
                if (!groups["Other"]) groups["Other"] = [];
                groups["Other"].push(m);
            }
        }
        return Object.entries(groups).map(([provider, providerModels]) => (
            <FormSelectOptionGroup key={provider} label={provider}>
                {providerModels.map((m) => (
                    <FormSelectOption
                        key={m}
                        value={m}
                        label={m.split("/").pop() || m}
                    />
                ))}
            </FormSelectOptionGroup>
        ));
    }
    return models.map((m) => <FormSelectOption key={m} value={m} label={m} />);
}
