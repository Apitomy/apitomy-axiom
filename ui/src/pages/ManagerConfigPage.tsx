import { useState, useEffect, useCallback, useRef } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Modal,
    ModalBody,
    ModalHeader,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import HelpIcon from "@patternfly/react-icons/dist/esm/icons/help-icon";
import { registerPlaceholderCompletions, MANAGER_PLACEHOLDERS } from "../components/PlaceholderCompletionProvider";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type ManagerConfig,
    fetchManagerConfig,
    updateManagerConfig,
} from "../config/api";

export function ManagerConfigPage() {
    const effectiveTheme = useEffectiveTheme();
    const [config, setConfig] = useState<ManagerConfig>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);
    const [helpOpen, setHelpOpen] = useState(false);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const templateEditorRef = useRef<any>(null);

    const insertPlaceholder = (name: string) => {
        const text = `{{${name}}}`;
        const editor = templateEditorRef.current;
        if (editor) {
            const position = editor.getPosition();
            if (position) {
                editor.executeEdits("placeholder-insert", [{
                    range: {
                        startLineNumber: position.lineNumber,
                        startColumn: position.column,
                        endLineNumber: position.lineNumber,
                        endColumn: position.column,
                    },
                    text,
                }]);
                editor.focus();
            }
        } else {
            // Fallback: append to the template text
            setConfig(prev => ({
                ...prev,
                promptTemplate: (prev.promptTemplate || "") + text,
            }));
        }
        setDirty(true);
        setHelpOpen(false);
    };

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
                            isDarkTheme={effectiveTheme === "dark"}
                            isLineNumbersVisible
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Prompt Template</TabTitleText>}>
                    <TabContent id="prompt-template-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <Flex alignItems={{ default: "alignItemsCenter" }}
                            style={{ marginBottom: "12px", gap: "8px" }}>
                            <FlexItem>
                                <span className="axiom-text-subtle">
                                    The prompt template is sent as the user message for each event
                                    evaluation. Placeholders are substituted at runtime.
                                </span>
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" aria-label="Placeholder reference"
                                    onClick={() => setHelpOpen(true)}>
                                    <HelpIcon />
                                </Button>
                            </FlexItem>
                        </Flex>
                        <CodeEditor
                            code={config.promptTemplate || ""}
                            onCodeChange={(v) => { setConfig({ ...config, promptTemplate: v }); setDirty(true); }}
                            language={Language.markdown}
                            height="500px"
                            isDarkTheme={effectiveTheme === "dark"}
                            isLineNumbersVisible
                            onEditorDidMount={(editor, monaco) => {
                                templateEditorRef.current = editor;
                                registerPlaceholderCompletions(editor, monaco, "markdown", MANAGER_PLACEHOLDERS);
                            }}
                        />
                    </TabContent>
                </Tab>
            </Tabs>

            <Modal isOpen={helpOpen} onClose={() => setHelpOpen(false)} variant="large"
                aria-label="Prompt template placeholder reference">
                <ModalHeader title="Prompt Template Placeholders" />
                <ModalBody>
                    <p style={{ marginBottom: "16px" }}>
                        These placeholders are replaced with actual values when the Manager
                        evaluates an event. Click a placeholder to insert it into the prompt
                        template at the cursor position.
                    </p>
                    <Table aria-label="Template placeholders" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Placeholder</Th>
                                <Th>Description</Th>
                                <Th>Example Value</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {[
                                { name: "actionTypes", desc: "Formatted markdown list of all configured action types with their names, execution modes, and descriptions", example: "Rendered as a markdown section" },
                                { name: "agents", desc: "Formatted markdown list of all configured AI agents with their names and types", example: "Rendered as a markdown section" },
                                { name: "source", desc: "The source system that produced the event", example: "github, jira" },
                                { name: "eventType", desc: "The normalized event type using dot notation", example: "issue.created, pr.merged" },
                                { name: "ref", desc: "Full URL uniquely identifying the subject of the event", example: "https://github.com/owner/repo/issues/42" },
                                { name: "issueRef", desc: "Alias for {{ref}} (backward compatibility)", example: "Same as {{ref}}" },
                                { name: "repository", desc: "Alias for {{ref}} (backward compatibility)", example: "Same as {{ref}}" },
                                { name: "payload", desc: "The normalized, typed event payload as JSON. Structure depends on event type.", example: '{"issue":{"title":"Fix bug","state":"open"}}' },
                                { name: "projectContext", desc: "Existing project details including name, status, type, and the 10 most recent tasks", example: "Rendered as a markdown section" },
                            ].map((p) => (
                                <Tr key={p.name} isClickable
                                    onRowClick={() => insertPlaceholder(p.name)}>
                                    <Td>
                                        <code style={{ cursor: "pointer", color: "var(--pf-v6-global--link--Color, #0066cc)" }}>
                                            {`{{${p.name}}}`}
                                        </code>
                                    </Td>
                                    <Td>{p.desc}</Td>
                                    <Td><code>{p.example}</code></Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                </ModalBody>
            </Modal>
        </PageSection>
    );
}
