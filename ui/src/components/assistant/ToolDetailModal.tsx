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

interface ToolParam {
    name: string;
    type?: string;
    description?: string;
    required?: boolean;
}

interface ToolDetailModalProps {
    isOpen: boolean;
    onClose: () => void;
    name: string;
    content: Record<string, unknown>;
    errors?: string[];
}

export function ToolDetailModal({ isOpen, onClose, name, content, errors }: ToolDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [activeTab, setActiveTab] = useState(0);

    const description = (content.description as string) || "";
    const parameters = (content.parameters as ToolParam[]) || [];
    const scriptTemplate = (content.scriptTemplate as string) || "";
    const labels = (content.labels as string[]) || [];

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="large"
            aria-label={`Tool: ${name}`}
            style={{ height: "80vh" }}
        >
            <ModalHeader title={`Tool: ${name}`} />
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
                                {labels.length > 0 && (
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Labels</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {labels.map((l) => (
                                                <Label key={l} isCompact style={{ marginRight: 4 }}>
                                                    {l}
                                                </Label>
                                            ))}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                )}
                            </DescriptionList>

                            {parameters.length > 0 && (
                                <>
                                    <DescriptionList isHorizontal isCompact style={{ marginTop: 16 }}>
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Parameters</DescriptionListTerm>
                                            <DescriptionListDescription>{" "}</DescriptionListDescription>
                                        </DescriptionListGroup>
                                    </DescriptionList>
                                    <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "13px" }}>
                                        <thead>
                                            <tr style={{ borderBottom: "2px solid var(--pf-t--global--border--color--default, #d2d2d2)" }}>
                                                <th style={{ textAlign: "left", padding: "6px 8px" }}>Name</th>
                                                <th style={{ textAlign: "left", padding: "6px 8px" }}>Type</th>
                                                <th style={{ textAlign: "left", padding: "6px 8px" }}>Description</th>
                                                <th style={{ textAlign: "left", padding: "6px 8px" }}>Required</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {parameters.map((p) => (
                                                <tr key={p.name} style={{ borderBottom: "1px solid var(--pf-t--global--border--color--default, #d2d2d2)" }}>
                                                    <td style={{ padding: "6px 8px", fontFamily: "monospace" }}>{p.name}</td>
                                                    <td style={{ padding: "6px 8px" }}>{p.type || "string"}</td>
                                                    <td style={{ padding: "6px 8px" }}>{p.description || "—"}</td>
                                                    <td style={{ padding: "6px 8px" }}>{p.required ? "Yes" : "No"}</td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </>
                            )}
                        </div>
                    </Tab>
                    {scriptTemplate ? (
                        <Tab eventKey={1} title={<TabTitleText>Script Template</TabTitleText>}>
                            <div style={{ paddingTop: 16, flex: "1 1 0", minHeight: 0 }}>
                                <CodeEditor
                                    code={scriptTemplate}
                                    language={Language.shell}
                                    isDarkTheme={effectiveTheme === "dark"}
                                    height="100%"
                                    isReadOnly
                                    isLineNumbersVisible
                                />
                            </div>
                        </Tab>
                    ) : null}
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
