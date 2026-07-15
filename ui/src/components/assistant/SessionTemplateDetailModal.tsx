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
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";

import { ValidationProblemsTab } from "./ValidationProblemsTab";
import "./ActionTypeDetailModal.css";

interface SessionTemplateDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
    errors?: string[];
}

export function SessionTemplateDetailModal({ isOpen, onClose, name, content, errors }: SessionTemplateDetailModalProps) {
    const [activeTab, setActiveTab] = useState(0);

    const templateId = (content.templateId as string) || "";
    const templateName = (content.name as string) || "";
    const description = (content.description as string) || "";
    const model = (content.model as string) || "";
    const mcpServers = (content.mcpServers as string[]) || [];
    const allowedTools = (content.allowedTools as string[]) || [];

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="large"
            aria-label={`Session Template: ${name}`}
            style={{ height: "80vh" }}
        >
            <ModalHeader title={`Session Template: ${name}`} />
            <ModalBody className="assistant-tabbed-modal-body">
                <Tabs activeKey={activeTab}
                    onSelect={(_e, key) => setActiveTab(key as number)}>
                    <Tab eventKey={0} title={<TabTitleText>Details</TabTitleText>}>
                        <div style={{ paddingTop: 16 }}>
                            <DescriptionList isHorizontal isCompact>
                                {templateId && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Template ID</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <code>{templateId}</code>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Name</DescriptionListTerm>
                                    <DescriptionListDescription>{templateName || "—"}</DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Description</DescriptionListTerm>
                                    <DescriptionListDescription>{description || "—"}</DescriptionListDescription>
                                </DescriptionListGroup>
                                {model && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Model</DescriptionListTerm>
                                        <DescriptionListDescription>{model}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {mcpServers.length > 0 && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>MCP Servers</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                                                {mcpServers.map((s) => (
                                                    <Label key={s} isCompact color="purple">
                                                        {s}
                                                    </Label>
                                                ))}
                                            </div>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {allowedTools.length > 0 && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Allowed Tools</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                                                {allowedTools.map((t) => (
                                                    <Label key={t} isCompact color="blue">
                                                        {t}
                                                    </Label>
                                                ))}
                                            </div>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                            </DescriptionList>
                        </div>
                    </Tab>
                    <Tab eventKey={1} title={<TabTitleText>Source</TabTitleText>}>
                        <div style={{ paddingTop: 16, flex: "1 1 0", minHeight: 0 }}>
                            <CodeEditor
                                code={JSON.stringify(content, null, 2)}
                                language={Language.json}
                                height="100%"
                                isReadOnly
                                isLineNumbersVisible
                            />
                        </div>
                    </Tab>
                    {(errors?.length ?? 0) > 0 && (
                        <Tab eventKey={2} title={
                            <TabTitleText>
                                <ExclamationCircleIcon style={{ color: "#c9190b", marginRight: 6 }} />
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
