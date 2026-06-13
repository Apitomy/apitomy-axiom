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

interface ReportDefinitionDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
}

export function ReportDefinitionDetailModal({
    isOpen,
    onClose,
    name,
    content,
}: ReportDefinitionDetailModalProps) {
    const [activeTab, setActiveTab] = useState(0);

    const description = (content.description as string) || "";
    const schedule = (content.schedule as string) || "none";
    const scheduleTime = (content.scheduleTime as string) || "";
    const scheduleDayOfWeek = (content.scheduleDayOfWeek as string) || "";
    const timeWindow = (content.timeWindow as string) || "";
    const promptTemplate = (content.promptTemplate as string) || "";
    const rawAllowedTools = content.allowedTools;
    const timeoutSeconds = content.timeoutSeconds as number | undefined;

    const toolsList = Array.isArray(rawAllowedTools)
        ? rawAllowedTools as string[]
        : typeof rawAllowedTools === "string" && rawAllowedTools
            ? rawAllowedTools.split(",").map((t) => t.trim()).filter(Boolean)
            : [];

    const formatSchedule = () => {
        const parts = [schedule];
        if (scheduleTime) parts.push(`at ${scheduleTime}`);
        if (scheduleDayOfWeek) parts.push(`on ${scheduleDayOfWeek}`);
        return parts.join(" ");
    };

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="large"
            aria-label={`Report Definition: ${name}`}
            style={{ height: "80vh" }}
        >
            <ModalHeader title={`Report Definition: ${name}`} />
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
                                    <DescriptionListTerm>Schedule</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <Label isCompact color="blue">{formatSchedule()}</Label>
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                {timeWindow && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm style={{ whiteSpace: "nowrap" }}>Time Window</DescriptionListTerm>
                                        <DescriptionListDescription>{timeWindow}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {timeoutSeconds !== undefined && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Timeout</DescriptionListTerm>
                                        <DescriptionListDescription>{timeoutSeconds}s</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
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
                            </DescriptionList>
                        </div>
                    </Tab>
                    {promptTemplate ? (
                        <Tab eventKey={1} title={<TabTitleText>Prompt Template</TabTitleText>}>
                            <div style={{ paddingTop: 16, flex: "1 1 0", minHeight: 0 }}>
                                <CodeEditor
                                    code={promptTemplate}
                                    language={Language.markdown}
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
