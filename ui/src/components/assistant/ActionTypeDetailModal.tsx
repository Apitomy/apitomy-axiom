import {
    Modal,
    ModalBody,
    ModalHeader,
    DescriptionList,
    DescriptionListGroup,
    DescriptionListTerm,
    DescriptionListDescription,
    Label,
    Tab,
    Tabs,
    TabTitleText,
    EmptyState,
    EmptyStateBody,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useState } from "react";
import { useEffectiveTheme } from "../../hooks/useTheme";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import { ValidationProblemsTab } from "./ValidationProblemsTab";
import "./ActionTypeDetailModal.css";

interface ActionTypeDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
    errors?: string[];
}

function FieldsTable({ fields }: { fields: { name: string; type: string; required?: boolean; description?: string }[] }) {
    if (fields.length === 0) {
        return (
            <EmptyState variant="sm">
                <EmptyStateBody>No fields declared.</EmptyStateBody>
            </EmptyState>
        );
    }
    return (
        <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {fields.map((f, i) => (
                <div key={i} style={{
                    display: "flex", alignItems: "center", gap: 8,
                    padding: "8px 12px",
                    backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                    borderRadius: 4,
                }}>
                    <span style={{ fontWeight: 600, fontSize: 13, minWidth: 140 }}>{f.name}</span>
                    <Label isCompact color="blue">{f.type}</Label>
                    {f.required && <Label isCompact color="orange">required</Label>}
                    {f.description && (
                        <span className="axiom-text-subtle" style={{ fontSize: 13, flex: 1 }}>{f.description}</span>
                    )}
                </div>
            ))}
        </div>
    );
}

export function ActionTypeDetailModal({ isOpen, onClose, name, content, errors }: ActionTypeDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [activeTab, setActiveTab] = useState(0);

    const description = (content.description as string) || "";
    const executionMode = (content.executionMode as string) || "agent";
    const userTriggerable = content.userTriggerable as boolean ?? false;
    const managerTriggerable = content.managerTriggerable as boolean ?? false;
    const emitsEvent = content.emitsEvent as boolean ?? false;
    const workflowEnabled = content.workflowEnabled as boolean ?? false;
    const inputs = Array.isArray(content.inputs) ? content.inputs as { name: string; type: string; required?: boolean; description?: string }[] : [];
    const outputs = Array.isArray(content.outputs) ? content.outputs as { name: string; type: string; required?: boolean; description?: string }[] : [];
    const rawAllowedTools = content.allowedTools;
    const promptTemplate = (content.promptTemplate as string) || "";
    const scriptTemplate = (content.scriptTemplate as string) || "";
    const model = (content.model as string) || "";
    const engine = (content.engine as string) || "";
    const maxSteps = content.maxSteps as number | undefined;
    const maxBudgetUsd = content.maxBudgetUsd as number | undefined;
    const timeoutSeconds = content.timeoutSeconds as number | undefined;

    const toolsList = Array.isArray(rawAllowedTools)
        ? rawAllowedTools as string[]
        : typeof rawAllowedTools === "string" && rawAllowedTools
            ? rawAllowedTools.split(",").map((t) => t.trim()).filter(Boolean)
            : [];

    const isAgentMode = executionMode === "agent";
    const templateContent = isAgentMode ? promptTemplate : scriptTemplate;
    const templateLabel = isAgentMode ? "Prompt Template" : "Script Template";
    const templateLanguage = isAgentMode ? Language.markdown : Language.shell;

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="large"
            aria-label={`Action Type: ${name}`}
            style={{ height: "80vh" }}
        >
            <ModalHeader title={`Action Type: ${name}`} />
            <ModalBody className="assistant-tabbed-modal-body">
                <Tabs activeKey={activeTab}
                    onSelect={(_e, key) => setActiveTab(key as number)}>
                    <Tab eventKey={0} title={<TabTitleText>Details</TabTitleText>}>
                        <div style={{ paddingTop: 16 }}>
                            <DescriptionList isHorizontal isCompact>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Name</DescriptionListTerm>
                                    <DescriptionListDescription>{name}</DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Description</DescriptionListTerm>
                                    <DescriptionListDescription>{description || "—"}</DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm style={{ whiteSpace: "nowrap" }}>Execution Mode</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <Label isCompact color={isAgentMode ? "blue" : "orange"}>
                                            {executionMode}
                                        </Label>
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Flags</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {userTriggerable && <Label isCompact color="green" style={{ marginRight: 4 }}>User Triggerable</Label>}
                                        {managerTriggerable && <Label isCompact color="blue" style={{ marginRight: 4 }}>Manager Triggerable</Label>}
                                        {emitsEvent && <Label isCompact color="purple" style={{ marginRight: 4 }}>Emits Event</Label>}
                                        {workflowEnabled && <Label isCompact color="teal" style={{ marginRight: 4 }}>Workflow Enabled</Label>}
                                        {!userTriggerable && !managerTriggerable && !emitsEvent && !workflowEnabled && "—"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                {toolsList.length > 0 && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm style={{ whiteSpace: "nowrap" }}>Allowed Tools</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                                                {toolsList.map((tool) => (
                                                    <Label key={tool} isCompact
                                                        color={tool.startsWith("@") ? "green" : "blue"}>
                                                        {tool}
                                                    </Label>
                                                ))}
                                            </div>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {model && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Model</DescriptionListTerm>
                                        <DescriptionListDescription>{model}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {engine && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Engine</DescriptionListTerm>
                                        <DescriptionListDescription>{engine}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {maxSteps != null && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm style={{ whiteSpace: "nowrap" }}>Max Steps</DescriptionListTerm>
                                        <DescriptionListDescription>{maxSteps}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {maxBudgetUsd != null && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm style={{ whiteSpace: "nowrap" }}>Max Budget (USD)</DescriptionListTerm>
                                        <DescriptionListDescription>${maxBudgetUsd.toFixed(2)}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {timeoutSeconds != null && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm style={{ whiteSpace: "nowrap" }}>Timeout (s)</DescriptionListTerm>
                                        <DescriptionListDescription>{timeoutSeconds}s</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                            </DescriptionList>
                        </div>
                    </Tab>
                    {templateContent ? (
                        <Tab eventKey={1} title={<TabTitleText>{templateLabel}</TabTitleText>}>
                            <div style={{ paddingTop: 16, flex: "1 1 0", minHeight: 0 }}>
                                <CodeEditor
                                    code={templateContent}
                                    language={templateLanguage}
                                    isDarkTheme={effectiveTheme === "dark"}
                                    height="100%"
                                    isReadOnly
                                    isLineNumbersVisible
                                    options={isAgentMode ? { wordWrap: "on" } : undefined}
                                />
                            </div>
                        </Tab>
                    ) : null}
                    {workflowEnabled && inputs.length > 0 && (
                        <Tab eventKey={2} title={<TabTitleText>Inputs ({inputs.length})</TabTitleText>}>
                            <div style={{ paddingTop: 16 }}>
                                <FieldsTable fields={inputs} />
                            </div>
                        </Tab>
                    )}
                    {workflowEnabled && outputs.length > 0 && (
                        <Tab eventKey={3} title={<TabTitleText>Outputs ({outputs.length})</TabTitleText>}>
                            <div style={{ paddingTop: 16 }}>
                                <FieldsTable fields={outputs} />
                            </div>
                        </Tab>
                    )}
                    {(errors?.length ?? 0) > 0 && (
                        <Tab eventKey={4} title={
                            <TabTitleText>
                                <ExclamationCircleIcon className="axiom-icon-danger" style={{ marginRight: 6 }} />
                                Problems ({errors!.length})
                            </TabTitleText>
                        }>
                            <ValidationProblemsTab errors={errors} />
                        </Tab>
                    )}
                </Tabs>
            </ModalBody>
        </Modal>
    );
}
