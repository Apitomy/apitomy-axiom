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
import { useEffectiveTheme } from "../../hooks/useTheme";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";

import { ValidationProblemsTab } from "./ValidationProblemsTab";
import "./ActionTypeDetailModal.css";

interface EventSourceDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
    errors?: string[];
}

export function EventSourceDetailModal({ isOpen, onClose, name, content, errors }: EventSourceDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [activeTab, setActiveTab] = useState(0);

    const description = (content.description as string) || "";
    const sourceType = (content.sourceType as string) || "";
    const enabled = content.enabled as boolean ?? false;
    const pollInterval = content.pollInterval as number | undefined;
    const secretName = (content.secretName as string) || "";
    const configuration = content.configuration as Record<string, unknown> | undefined;
    const labels = (content.labels as string[]) || [];

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="large"
            aria-label={`Event Source: ${name}`}
            style={{ height: "80vh" }}
        >
            <ModalHeader title={`Event Source: ${name}`} />
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
                                    <DescriptionListTerm>Source Type</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <Label isCompact color={sourceType === "github" ? "blue" : "purple"}>
                                            {sourceType || "—"}
                                        </Label>
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Enabled</DescriptionListTerm>
                                    <DescriptionListDescription>{enabled ? "Yes" : "No"}</DescriptionListDescription>
                                </DescriptionListGroup>
                                {pollInterval !== undefined && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Poll Interval</DescriptionListTerm>
                                        <DescriptionListDescription>{pollInterval}s</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {secretName && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Secret</DescriptionListTerm>
                                        <DescriptionListDescription>{secretName}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {configuration && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Configuration</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <code>{JSON.stringify(configuration)}</code>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                                {labels.length > 0 && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Labels</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                                                {labels.map((l) => (
                                                    <Label key={l} isCompact color="grey">
                                                        {l}
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
                                isDarkTheme={effectiveTheme === "dark"}
                                height="100%"
                                isReadOnly
                                isLineNumbersVisible
                            />
                        </div>
                    </Tab>
                    {(errors?.length ?? 0) > 0 && (
                        <Tab eventKey={2} title={
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
