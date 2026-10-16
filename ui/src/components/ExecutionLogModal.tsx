import { useState, useEffect } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { fetchTaskExecutionLog, fetchActivityLogDetails } from "../config/api";

interface ExecutionLogModalProps {
    isOpen: boolean;
    /** For task logs: the subject ID */
    subjectId?: number | null;
    /** For task logs: the task ID */
    taskId?: number | null;
    /** For activity log details : the activity entry ID */
    activityId?: number | null;
    onClose: () => void;
}

/**
 * Modal that displays an execution log.
 */
export function ExecutionLogModal({ isOpen, subjectId, taskId, activityId,
                                     onClose }: ExecutionLogModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [content, setContent] = useState("");
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!isOpen) return;

        setLoading(true);
        setContent("");

        let fetchPromise: Promise<string>;
        if (activityId != null) {
            fetchPromise = fetchActivityLogDetails(activityId);
        } else if (subjectId != null && taskId != null) {
            fetchPromise = fetchTaskExecutionLog(subjectId, taskId);
        } else {
            setContent("No log source specified.");
            setLoading(false);
            return;
        }

        fetchPromise
            .then(setContent)
            .catch((err) => setContent("Error loading log: " + err.message))
            .finally(() => setLoading(false));
    }, [isOpen, subjectId, taskId, activityId]);

    const handleClose = () => {
        onClose();
        setContent("");
    };

    const title = activityId != null
        ? `Execution Log — Activity #${activityId}`
        : `Execution Log — Task #${taskId}`;

    return (
        <Modal isOpen={isOpen} onClose={handleClose} variant="large">
            <ModalHeader title={title} />
            <ModalBody>
                {loading ? (
                    <EmptyState>
                        <EmptyStateBody>Loading execution log...</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <CodeEditor
                        code={content}
                        language={Language.markdown}
                        isDarkTheme={effectiveTheme === "dark"}
                        height="600px"
                        isReadOnly
                        isLineNumbersVisible
                    />
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="link" onClick={handleClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}
