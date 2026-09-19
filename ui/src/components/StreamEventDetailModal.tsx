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
import { type StreamEvent } from "../config/api";

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
};

interface StreamEventDetailModalProps {
    event: StreamEvent | null;
    onClose: () => void;
}

/**
 * Modal dialog that displays full details of a stream event, including
 * metadata, the normalized payload, and optional source data in read-only
 * code editors.
 */
export function StreamEventDetailModal({ event, onClose }: StreamEventDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();

    const formatJson = (data?: Record<string, unknown>): string => {
        if (!data) return "";
        try {
            return JSON.stringify(data, null, 2);
        } catch {
            return "";
        }
    };

    const actorLogin = event?.actor?.login as string | undefined;
    const actorDisplayName = event?.actor?.displayName as string | undefined;
    const actorLabel = [actorLogin, actorDisplayName].filter(Boolean).join(" — ") || undefined;

    return (
        <Modal isOpen={event !== null} onClose={onClose} variant="large"
            aria-label="Stream event details">
            <ModalHeader
                title={event?.type ?? "Stream Event"}
                description={event
                    ? `${event.source} event — ${new Date(event.timestamp).toLocaleString()}`
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
                                            <Label isCompact
                                                color={SOURCE_COLORS[event.source] || "grey"}>
                                                {event.source}
                                            </Label>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Connection</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {event.connectionId}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Event Type</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <Label isCompact>{event.type}</Label>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    {event.ref && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Ref</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                <a href={event.ref} target="_blank" rel="noopener noreferrer">
                                                    {event.ref}
                                                </a>
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                    )}
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Timestamp</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {new Date(event.timestamp).toLocaleString()}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    {actorLabel && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Actor</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                {actorLabel}
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                    )}
                                    {event.sourceEventId && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Source Event ID</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                {event.sourceEventId}
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
                            code={formatJson(event.payload)}
                            language={Language.json}
                            isDarkTheme={effectiveTheme === "dark"}
                            height="400px"
                            isReadOnly
                            isLineNumbersVisible
                        />

                        {event.sourceData && (
                            <>
                                <Title headingLevel="h4" size="md"
                                    style={{ marginTop: "16px", marginBottom: "8px" }}>
                                    Source Data
                                </Title>
                                <CodeEditor
                                    code={formatJson(event.sourceData)}
                                    language={Language.json}
                                    isDarkTheme={effectiveTheme === "dark"}
                                    height="300px"
                                    isReadOnly
                                    isLineNumbersVisible
                                />
                            </>
                        )}
                    </>
                )}
            </ModalBody>
        </Modal>
    );
}
