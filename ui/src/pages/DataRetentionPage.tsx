import { useState, useEffect, useCallback } from "react";
import {
    Alert,
    AlertActionCloseButton,
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

/** History retention settings, where 0 keeps the records forever. */
const HISTORY_FIELDS: {
    field: keyof RetentionConfig;
    id: string;
    label: string;
    help: string;
}[] = [
    {
        field: "scheduledJobRunRetentionDays",
        id: "job-run-retention",
        label: "Scheduled job runs",
        help: "Days to retain finished scheduled job runs.",
    },
    {
        field: "reportRetentionDays",
        id: "report-retention",
        label: "Reports",
        help: "Days to retain finished reports.",
    },
    {
        field: "workflowRunRetentionDays",
        id: "workflow-run-retention",
        label: "Workflow runs",
        help: "Days to retain finished workflow runs. Running and waiting runs are never deleted.",
    },
    {
        field: "activityLogRetentionDays",
        id: "activity-log-retention",
        label: "Activity log",
        help: "Days to retain activity log entries.",
    },
    {
        field: "aiUsageRetentionDays",
        id: "ai-usage-retention",
        label: "AI usage",
        help: "Days to retain AI usage (cost) records.",
    },
];

export function DataRetentionPage() {
    const [config, setConfig] = useState<RetentionConfig>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);

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
        setSaveError(null);
        updateRetentionConfig(config)
            .then((c) => { setConfig(c); setDirty(false); })
            .catch((e: unknown) => {
                console.error(e);
                setSaveError(e instanceof Error ? e.message : String(e));
            })
            .finally(() => setSaving(false));
    };

    const updateField = (field: keyof RetentionConfig, value: number, min = 1) => {
        const clamped = Math.max(min, Math.round(Number.isFinite(value) ? value : min));
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

            {saveError && (
                <Alert
                    variant="danger"
                    isInline
                    title="Could not save retention settings"
                    actionClose={<AlertActionCloseButton onClose={() => setSaveError(null)} />}
                    style={{ marginBottom: "16px" }}
                >
                    {saveError}
                </Alert>
            )}

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

                <Title headingLevel="h2" size="md" style={{ marginTop: "16px" }}>History</Title>
                <p className="axiom-text-subtle">
                    Set a value of 0 to keep these records forever (the default).
                </p>

                {HISTORY_FIELDS.map(({ field, id, label, help }) => {
                    const value = config[field] ?? 0;
                    return (
                        <FormGroup key={field} label={label} fieldId={id}>
                            <NumberInput
                                id={id}
                                value={value}
                                min={0}
                                onMinus={() => updateField(field, value - 1, 0)}
                                onPlus={() => updateField(field, value + 1, 0)}
                                onChange={(event) => updateField(field,
                                    Number((event.target as HTMLInputElement).value), 0)}
                                widthChars={4}
                            />
                            <FormHelperText>
                                <HelperText>
                                    <HelperTextItem>
                                        {help} {value === 0 ? "Currently kept forever." : ""}
                                    </HelperTextItem>
                                </HelperText>
                            </FormHelperText>
                        </FormGroup>
                    );
                })}
            </Form>
        </PageSection>
    );
}
