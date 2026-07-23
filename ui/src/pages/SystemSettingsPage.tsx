import { useState, useEffect, useCallback } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormSection,
    NumberInput,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextInput,
    Title,
} from "@patternfly/react-core";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type SystemSettings,
    fetchSystemSettings,
    updateSystemSettings,
} from "../config/api";

export function SystemSettingsPage() {
    const [settings, setSettings] = useState<SystemSettings>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const loadSettings = useCallback(() => {
        setLoading(true);
        fetchSystemSettings()
            .then((s) => { setSettings(s); setDirty(false); })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { loadSettings(); }, [loadSettings]);

    const handleSave = () => {
        setSaving(true);
        updateSystemSettings(settings)
            .then((s) => { setSettings(s); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const update = (field: keyof SystemSettings, value: string | number | undefined) => {
        setSettings({ ...settings, [field]: value });
        setDirty(true);
    };

    const numInput = (
        field: keyof SystemSettings,
        min: number = 1,
        step: number = 1,
    ) => {
        const val = (settings[field] as number | undefined) ?? 0;
        return (
            <NumberInput
                value={val}
                min={min}
                onMinus={() => update(field, Math.max(min, val - step))}
                onPlus={() => update(field, val + step)}
                onChange={(event: React.FormEvent<HTMLInputElement>) => {
                    const n = parseInt((event.target as HTMLInputElement).value, 10);
                    if (!isNaN(n)) update(field, Math.max(min, n));
                }}
                widthChars={8}
            />
        );
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading system settings...</EmptyStateBody></EmptyState>
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
                    <Title headingLevel="h1" size="lg">System Settings</Title>
                </FlexItem>
                <FlexItem>
                    <Button
                        variant="primary" icon={<SaveIcon />}
                        onClick={handleSave}
                        isDisabled={!dirty || saving}
                        isLoading={saving}
                    >
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>General</TabTitleText>}>
                    <TabContent id="general-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <Form isHorizontal>
                            <FormSection title="AI Engine">
                                <FormGroup label="Active Engine" fieldId="ai-engine">
                                    <TextInput
                                        id="ai-engine"
                                        value={settings.aiEngine ?? ""}
                                        onChange={(_e, v) => update("aiEngine", v)}
                                    />
                                </FormGroup>
                            </FormSection>
                            <FormSection title="Event Source Logs">
                                <FormGroup label="Retention (days)" fieldId="retention-days">
                                    {numInput("eventSourceLogRetentionDays")}
                                </FormGroup>
                            </FormSection>
                            <FormSection title="Script Execution">
                                <FormGroup label="Timeout (seconds)" fieldId="script-timeout">
                                    {numInput("scriptTimeoutSeconds")}
                                </FormGroup>
                            </FormSection>
                        </Form>
                    </TabContent>
                </Tab>

                <Tab eventKey={1} title={<TabTitleText>Manager</TabTitleText>}>
                    <TabContent id="manager-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <Form isHorizontal>
                            <FormGroup label="Max Turns" fieldId="mgr-max-turns">
                                {numInput("managerMaxTurns")}
                            </FormGroup>
                            <FormGroup label="Confidence Threshold" fieldId="mgr-confidence"
                                helperText="Value between 0.0 and 1.0">
                                <NumberInput
                                    value={settings.managerConfidenceThreshold ?? 0.7}
                                    min={0} max={1}
                                    onMinus={() => update("managerConfidenceThreshold",
                                        Math.max(0, (settings.managerConfidenceThreshold ?? 0.7) - 0.05))}
                                    onPlus={() => update("managerConfidenceThreshold",
                                        Math.min(1, (settings.managerConfidenceThreshold ?? 0.7) + 0.05))}
                                    onChange={(event: React.FormEvent<HTMLInputElement>) => {
                                        const n = parseFloat((event.target as HTMLInputElement).value);
                                        if (!isNaN(n)) update("managerConfidenceThreshold",
                                            Math.max(0, Math.min(1, n)));
                                    }}
                                    widthChars={8}
                                />
                            </FormGroup>
                            <FormGroup label="Timeout (seconds)" fieldId="mgr-timeout">
                                {numInput("managerTimeoutSeconds")}
                            </FormGroup>
                            <FormGroup label="Model Override" fieldId="mgr-model"
                                helperText="Leave empty to use the engine default">
                                <TextInput
                                    id="mgr-model"
                                    value={settings.managerModel ?? ""}
                                    onChange={(_e, v) => update("managerModel", v)}
                                    placeholder="(engine default)"
                                />
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>

                <Tab eventKey={2} title={<TabTitleText>Claude Code</TabTitleText>}>
                    <TabContent id="claude-code-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <Form isHorizontal>
                            <FormGroup label="Max Turns" fieldId="cc-max-turns">
                                {numInput("claudeCodeMaxTurns")}
                            </FormGroup>
                            <FormGroup label="Max Budget (USD)" fieldId="cc-budget">
                                <NumberInput
                                    value={settings.claudeCodeMaxBudgetUsd ?? 5.0}
                                    min={0}
                                    onMinus={() => update("claudeCodeMaxBudgetUsd",
                                        Math.max(0, (settings.claudeCodeMaxBudgetUsd ?? 5.0) - 0.5))}
                                    onPlus={() => update("claudeCodeMaxBudgetUsd",
                                        (settings.claudeCodeMaxBudgetUsd ?? 5.0) + 0.5)}
                                    onChange={(event: React.FormEvent<HTMLInputElement>) => {
                                        const n = parseFloat((event.target as HTMLInputElement).value);
                                        if (!isNaN(n)) update("claudeCodeMaxBudgetUsd", Math.max(0, n));
                                    }}
                                    widthChars={8}
                                />
                            </FormGroup>
                            <FormGroup label="Timeout (seconds)" fieldId="cc-timeout">
                                {numInput("claudeCodeTimeoutSeconds")}
                            </FormGroup>
                            <FormGroup label="Model Override" fieldId="cc-model"
                                helperText="Leave empty to use the engine default">
                                <TextInput
                                    id="cc-model"
                                    value={settings.claudeCodeModel ?? ""}
                                    onChange={(_e, v) => update("claudeCodeModel", v)}
                                    placeholder="(engine default)"
                                />
                            </FormGroup>
                            <FormGroup label="Available Models" fieldId="cc-models"
                                helperText="Comma-separated list of model names">
                                <TextInput
                                    id="cc-models"
                                    value={settings.claudeCodeAvailableModels ?? ""}
                                    onChange={(_e, v) => update("claudeCodeAvailableModels", v)}
                                />
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>

                <Tab eventKey={3} title={<TabTitleText>OpenCode</TabTitleText>}>
                    <TabContent id="opencode-tab" eventKey={3} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <Form isHorizontal>
                            <FormGroup label="Max Steps" fieldId="oc-max-steps">
                                {numInput("opencodeMaxSteps")}
                            </FormGroup>
                            <FormGroup label="Timeout (seconds)" fieldId="oc-timeout">
                                {numInput("opencodeTimeoutSeconds")}
                            </FormGroup>
                            <FormGroup label="Model Override" fieldId="oc-model"
                                helperText="Leave empty to use the engine default">
                                <TextInput
                                    id="oc-model"
                                    value={settings.opencodeModel ?? ""}
                                    onChange={(_e, v) => update("opencodeModel", v)}
                                    placeholder="(engine default)"
                                />
                            </FormGroup>
                            <FormGroup label="Available Models" fieldId="oc-models"
                                helperText="Comma-separated list of provider/model names">
                                <TextInput
                                    id="oc-models"
                                    value={settings.opencodeAvailableModels ?? ""}
                                    onChange={(_e, v) => update("opencodeAvailableModels", v)}
                                />
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>

                <Tab eventKey={4} title={<TabTitleText>Assistant</TabTitleText>}>
                    <TabContent id="assistant-tab" eventKey={4} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <Form isHorizontal>
                            <FormGroup label="Max Sessions" fieldId="asst-max-sessions">
                                {numInput("assistantMaxSessions")}
                            </FormGroup>
                            <FormGroup label="Timeout (seconds)" fieldId="asst-timeout">
                                {numInput("assistantTimeoutSeconds")}
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}
