import { useState, useEffect, useCallback } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { registerPlaceholderCompletions, MANAGER_PLACEHOLDERS } from "../components/PlaceholderCompletionProvider";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type ManagerConfig,
    fetchManagerConfig,
    updateManagerConfig,
} from "../config/api";

export function ManagerConfigPage() {
    const [config, setConfig] = useState<ManagerConfig>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const loadConfig = useCallback(() => {
        setLoading(true);
        fetchManagerConfig()
            .then((c) => { setConfig(c); setDirty(false); })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { loadConfig(); }, [loadConfig]);

    const handleSave = () => {
        setSaving(true);
        updateManagerConfig(config)
            .then((c) => { setConfig(c); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading manager configuration...</EmptyStateBody></EmptyState>
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
                    <Title headingLevel="h1" size="lg">Manager Configuration</Title>
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
                <Tab eventKey={0} title={<TabTitleText>System Prompt</TabTitleText>}>
                    <TabContent id="system-prompt-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                            The system prompt defines the Manager's role, behavior, and decision
                            format. It is sent as the system context for every Manager evaluation.
                        </p>
                        <CodeEditor
                            code={config.systemPrompt || ""}
                            onCodeChange={(v) => { setConfig({ ...config, systemPrompt: v }); setDirty(true); }}
                            language={Language.markdown}
                            height="500px"
                            isLineNumbersVisible
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Prompt Template</TabTitleText>}>
                    <TabContent id="prompt-template-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                            The prompt template is sent as the user message for each event evaluation.
                            Placeholders are substituted at runtime:{" "}
                            <code>{"{{actionTypes}}"}</code> (list of configured action types),{" "}
                            <code>{"{{actors}}"}</code> (list of configured actors),{" "}
                            <code>{"{{source}}"}</code>,{" "}
                            <code>{"{{eventType}}"}</code>,{" "}
                            <code>{"{{issueRef}}"}</code>,{" "}
                            <code>{"{{repository}}"}</code>,{" "}
                            <code>{"{{payload}}"}</code> (raw event JSON),{" "}
                            <code>{"{{projectContext}}"}</code> (existing project and recent tasks).
                        </p>
                        <CodeEditor
                            code={config.promptTemplate || ""}
                            onCodeChange={(v) => { setConfig({ ...config, promptTemplate: v }); setDirty(true); }}
                            language={Language.markdown}
                            height="500px"
                            isLineNumbersVisible
                            onEditorDidMount={(editor, monaco) => {
                                registerPlaceholderCompletions(editor, monaco, "markdown", MANAGER_PLACEHOLDERS);
                            }}
                        />
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}
