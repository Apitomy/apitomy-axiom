import {
    Modal,
    ModalBody,
    ModalHeader,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import { JsonPathTree } from "./JsonPathTree";
import { type StreamEvent } from "../config/api";

interface StreamEventDetailModalProps {
    event: StreamEvent | null;
    onClose: () => void;
    /**
     * When provided, JSON keys become clickable. Clicking a key constructs
     * the full dot-notation path (e.g., "event.actor.login"), calls this
     * callback, and closes the modal. Used by the subscription filter editor
     * to insert field paths into the expression.
     */
    onSelectPath?: (path: string) => void;
}

/**
 * Modal dialog that displays a stream event. When {@link onSelectPath} is
 * provided, the event renders as an interactive JSON tree with clickable
 * keys; otherwise it renders as a read-only JSON code editor.
 */
export function StreamEventDetailModal({ event, onClose, onSelectPath }: StreamEventDetailModalProps) {
    const effectiveTheme = useEffectiveTheme();

    const handleSelectPath = onSelectPath
        ? (path: string) => { onSelectPath(path); onClose(); }
        : undefined;

    return (
        <Modal isOpen={event !== null} onClose={onClose} variant="large"
            aria-label="Stream event details">
            <ModalHeader
                title={event?.type ?? "Stream Event"}
                description={event
                    ? `${event.source} · ${event.connectionId} · ${new Date(event.timestamp).toLocaleString()}${handleSelectPath ? " · Click a field to insert its path" : ""}`
                    : undefined}
            />
            <ModalBody>
                {event && (
                    handleSelectPath ? (
                        <JsonPathTree
                            data={event}
                            pathPrefix="event"
                            onSelectPath={handleSelectPath}
                        />
                    ) : (
                        <CodeEditor
                            code={JSON.stringify(event, null, 2)}
                            language={Language.json}
                            isDarkTheme={effectiveTheme === "dark"}
                            height="600px"
                            isReadOnly
                            isLineNumbersVisible
                        />
                    )
                )}
            </ModalBody>
        </Modal>
    );
}
