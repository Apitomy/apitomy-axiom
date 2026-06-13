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
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useState } from "react";
import "./ActionTypeDetailModal.css";

interface ActionTypeDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
}

export function ActionTypeDetailModal({ isOpen, onClose, name, content }: ActionTypeDetailModalProps) {
    const [activeTab, setActiveTab] = useState(0);

    const description = (content.description as string) || "";
    const executionMode = (content.executionMode as string) || "actor";
    const userTriggerable = content.userTriggerable as boolean ?? false;
    const managerTriggerable = content.managerTriggerable as boolean ?? false;
    const emitsEvent = content.emitsEvent as boolean ?? false;
    const rawAllowedTools = content.allowedTools;
    const promptTemplate = (content.promptTemplate as string) || "";
    const scriptTemplate = (content.scriptTemplate as string) || "";
    const model = (content.model as string) || "";
    const engine = (content.engine as string) || "";

    const toolsList = Array.isArray(rawAllowedTools)
        ? rawAllowedTools as string[]
        : typeof rawAllowedTools === "string" && rawAllowedTools
            ? rawAllowedTools.split(",").map((t) => t.trim()).filter(Boolean)
            : [];

    const isActorMode = executionMode === "actor";
    const templateContent = isActorMode ? promptTemplate : scriptTemplate;
    const templateLabel = isActorMode ? "Prompt Template" : "Script Template";
    const templateLanguage = isActorMode ? Language.markdown : Language.shell;

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
                                        <Label isCompact color={isActorMode ? "blue" : "orange"}>
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
                                        {!userTriggerable && !managerTriggerable && !emitsEvent && "—"}
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
                            </DescriptionList>
                        </div>
                    </Tab>
                    {templateContent ? (
                        <Tab eventKey={1} title={<TabTitleText>{templateLabel}</TabTitleText>}>
                            <div style={{ paddingTop: 16, flex: "1 1 0", minHeight: 0 }}>
                                <CodeEditor
                                    code={templateContent}
                                    language={templateLanguage}
                                    height="100%"
                                    isReadOnly
                                    isLineNumbersVisible
                                />
                            </div>
                        </Tab>
                    ) : null}
                </Tabs>
            </ModalBody>
        </Modal>
    );
}
