import {
    Card, CardBody,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import { type AxiomEvent } from "../config/api";

interface EventDetailModalProps {
    event: AxiomEvent | null;
    onClose: () => void;
}

/**
 * Modal dialog that displays full details of an event, including
 * metadata and the raw JSON payload in a read-only code editor.
 */
export function EventDetailModal({ event, onClose }: EventDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const formatPayload = (payload?: string): string => {
        if (!payload) return "";
        try {
            return JSON.stringify(JSON.parse(payload), null, 2);
        } catch {
            return payload;
        }
    };

    return (
        <Modal isOpen={event !== null} onClose={onClose} variant="large"
            aria-label="Event details">
            <ModalHeader
                title="Event Details"
                description={event
                    ? `Event #${event.id} — ${new Date(event.receivedAt).toLocaleString()}`
                    : undefined}
            />
            <ModalBody>
                {event && (
                    <>
                        <Card style={{ marginBottom: "8px" }}>
                            <CardBody>
                                <DescriptionList isHorizontal isCompact
                                    style={{ marginBottom: "16px" }}>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Source</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <Label isCompact>{event.source}</Label>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Event Type</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {event.eventType}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    {event.issueRef && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Issue</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                {event.issueRef}
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                    )}
                                    {event.repository && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Repository</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                {event.repository}
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                    )}
                                    {event.projectId && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Project</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                Project #{event.projectId}
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                    )}
                                </DescriptionList>
                            </CardBody>
                        </Card>

                        <Title headingLevel="h4" size="md" style={{ marginBottom: "8px" }}>
                            Payload
                        </Title>
                        <CodeEditor
                            code={formatPayload(event.payload)}
                            language={Language.json}
                            isDarkTheme={effectiveTheme === "dark"}
                            height="400px"
                            isReadOnly
                            isLineNumbersVisible
                        />
                    </>
                )}
            </ModalBody>
        </Modal>
    );
}
