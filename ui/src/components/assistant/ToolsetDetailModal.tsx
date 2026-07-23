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

interface ToolsetDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
    errors?: string[];
}

export function ToolsetDetailModal({ isOpen, onClose, name, content, errors }: ToolsetDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [activeTab, setActiveTab] = useState(0);

    const description = (content.description as string) || "";
    const tools = (content.tools as string[]) || [];

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="large"
            aria-label={`Toolset: ${name}`}
            style={{ height: "80vh" }}
        >
            <ModalHeader title={`Toolset: ${name}`} />
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
                                    <DescriptionListTerm>Tools</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {tools.length > 0 ? (
                                            <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                                                {tools.map((t) => (
                                                    <Label key={t} isCompact color="blue">
                                                        {t}
                                                    </Label>
                                                ))}
                                            </div>
                                        ) : "—"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
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
