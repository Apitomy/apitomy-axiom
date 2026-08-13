import { useState, useEffect, useCallback } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    NumberInput,
    PageSection,
    Title,
} from "@patternfly/react-core";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type RetentionConfig,
    fetchRetentionConfig,
    updateRetentionConfig,
} from "../config/api";

export function DataRetentionPage() {
    const [config, setConfig] = useState<RetentionConfig>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);

    const loadConfig = useCallback(() => {
        setLoading(true);
        fetchRetentionConfig()
            .then((c) => { setConfig(c); setDirty(false); })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { loadConfig(); }, [loadConfig]);

    const handleSave = () => {
        setSaving(true);
        updateRetentionConfig(config)
            .then((c) => { setConfig(c); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const updateField = (field: keyof RetentionConfig, value: number) => {
        const clamped = Math.max(1, Math.round(value));
        setConfig((prev) => ({ ...prev, [field]: clamped }));
        setDirty(true);
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Loading retention configuration...</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Data Retention</Title>
                </FlexItem>
                <FlexItem>
                    <Button
                        variant="primary"
                        icon={<SaveIcon />}
                        onClick={handleSave}
                        isDisabled={!dirty || saving}
                        isLoading={saving}
                    >
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <p className="axiom-text-subtle" style={{ marginBottom: "24px" }}>
                Configure how long Axiom retains data before automatic cleanup. A background
                job runs hourly to remove data older than the configured retention period.
                Processed event queue entries are always cleaned up after 1 day.
            </p>

            <Form style={{ maxWidth: "600px" }}>
                <FormGroup label="Closed projects" fieldId="closed-project-retention">
                    <NumberInput
                        id="closed-project-retention"
                        value={config.closedProjectRetentionDays ?? 90}
                        min={1}
                        onMinus={() => updateField("closedProjectRetentionDays",
                            (config.closedProjectRetentionDays ?? 90) - 1)}
                        onPlus={() => updateField("closedProjectRetentionDays",
                            (config.closedProjectRetentionDays ?? 90) + 1)}
                        onChange={(event) => updateField("closedProjectRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain closed projects before automatic deletion.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Traces" fieldId="trace-retention">
                    <NumberInput
                        id="trace-retention"
                        value={config.traceRetentionDays ?? 30}
                        min={1}
                        onMinus={() => updateField("traceRetentionDays",
                            (config.traceRetentionDays ?? 30) - 1)}
                        onPlus={() => updateField("traceRetentionDays",
                            (config.traceRetentionDays ?? 30) + 1)}
                        onChange={(event) => updateField("traceRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain execution traces, trace nodes, and tool execution
                                records.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Events" fieldId="event-retention">
                    <NumberInput
                        id="event-retention"
                        value={config.eventRetentionDays ?? 90}
                        min={1}
                        onMinus={() => updateField("eventRetentionDays",
                            (config.eventRetentionDays ?? 90) - 1)}
                        onPlus={() => updateField("eventRetentionDays",
                            (config.eventRetentionDays ?? 90) + 1)}
                        onChange={(event) => updateField("eventRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain ingested events (GitHub, Jira, internal).
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Event source logs" fieldId="event-source-log-retention">
                    <NumberInput
                        id="event-source-log-retention"
                        value={config.eventSourceLogRetentionDays ?? 7}
                        min={1}
                        onMinus={() => updateField("eventSourceLogRetentionDays",
                            (config.eventSourceLogRetentionDays ?? 7) - 1)}
                        onPlus={() => updateField("eventSourceLogRetentionDays",
                            (config.eventSourceLogRetentionDays ?? 7) + 1)}
                        onChange={(event) => updateField("eventSourceLogRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain event source poll log entries.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>
            </Form>
        </PageSection>
    );
}
