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

                <FormGroup label="Scheduled job runs" fieldId="job-run-retention">
                    <NumberInput
                        id="job-run-retention"
                        value={config.scheduledJobRunRetentionDays ?? 0}
                        min={0}
                        onMinus={() => updateField("scheduledJobRunRetentionDays",
                            (config.scheduledJobRunRetentionDays ?? 0) - 1, 0)}
                        onPlus={() => updateField("scheduledJobRunRetentionDays",
                            (config.scheduledJobRunRetentionDays ?? 0) + 1, 0)}
                        onChange={(event) => updateField("scheduledJobRunRetentionDays",
                            Number((event.target as HTMLInputElement).value), 0)}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain finished scheduled job runs. {(config.scheduledJobRunRetentionDays ?? 0) === 0 ? "Currently kept forever." : ""}
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Reports" fieldId="report-retention">
                    <NumberInput
                        id="report-retention"
                        value={config.reportRetentionDays ?? 0}
                        min={0}
                        onMinus={() => updateField("reportRetentionDays",
                            (config.reportRetentionDays ?? 0) - 1, 0)}
                        onPlus={() => updateField("reportRetentionDays",
                            (config.reportRetentionDays ?? 0) + 1, 0)}
                        onChange={(event) => updateField("reportRetentionDays",
                            Number((event.target as HTMLInputElement).value), 0)}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain finished reports. {(config.reportRetentionDays ?? 0) === 0 ? "Currently kept forever." : ""}
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Workflow runs" fieldId="workflow-run-retention">
                    <NumberInput
                        id="workflow-run-retention"
                        value={config.workflowRunRetentionDays ?? 0}
                        min={0}
                        onMinus={() => updateField("workflowRunRetentionDays",
                            (config.workflowRunRetentionDays ?? 0) - 1, 0)}
                        onPlus={() => updateField("workflowRunRetentionDays",
                            (config.workflowRunRetentionDays ?? 0) + 1, 0)}
                        onChange={(event) => updateField("workflowRunRetentionDays",
                            Number((event.target as HTMLInputElement).value), 0)}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain finished workflow runs. Running and waiting runs are never deleted. {(config.workflowRunRetentionDays ?? 0) === 0 ? "Currently kept forever." : ""}
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Activity log" fieldId="activity-log-retention">
                    <NumberInput
                        id="activity-log-retention"
                        value={config.activityLogRetentionDays ?? 0}
                        min={0}
                        onMinus={() => updateField("activityLogRetentionDays",
                            (config.activityLogRetentionDays ?? 0) - 1, 0)}
                        onPlus={() => updateField("activityLogRetentionDays",
                            (config.activityLogRetentionDays ?? 0) + 1, 0)}
                        onChange={(event) => updateField("activityLogRetentionDays",
                            Number((event.target as HTMLInputElement).value), 0)}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain activity log entries. {(config.activityLogRetentionDays ?? 0) === 0 ? "Currently kept forever." : ""}
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="AI usage" fieldId="ai-usage-retention">
                    <NumberInput
                        id="ai-usage-retention"
                        value={config.aiUsageRetentionDays ?? 0}
                        min={0}
                        onMinus={() => updateField("aiUsageRetentionDays",
                            (config.aiUsageRetentionDays ?? 0) - 1, 0)}
                        onPlus={() => updateField("aiUsageRetentionDays",
                            (config.aiUsageRetentionDays ?? 0) + 1, 0)}
                        onChange={(event) => updateField("aiUsageRetentionDays",
                            Number((event.target as HTMLInputElement).value), 0)}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain AI usage (cost) records. {(config.aiUsageRetentionDays ?? 0) === 0 ? "Currently kept forever." : ""}
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>
            </Form>
        </PageSection>
    );
}
