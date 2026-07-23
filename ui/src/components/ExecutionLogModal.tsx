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
import { fetchTaskExecutionLog, fetchActivityLogDetails, fetchReportExecutionLog } from "../config/api";

interface ExecutionLogModalProps {
    isOpen: boolean;
    /** For task logs: the project ID */
    projectId?: number | null;
    /** For task logs: the task ID */
    taskId?: number | null;
    /** For activity log details (e.g. manager evaluations): the activity entry ID */
    activityId?: number | null;
    /** For report logs: the report ID */
    reportId?: number | null;
    onClose: () => void;
}

/**
 * Modal that displays an execution log. Supports three fetch modes:
 * - By projectId + taskId (for task execution logs)
 * - By activityId (for manager evaluation logs stored in activity entry details)
 * - By reportId (for report generation logs)
 */
export function ExecutionLogModal({ isOpen, projectId, taskId, activityId, reportId,
                                     onClose }: ExecutionLogModalProps) {
    const effectiveTheme = useEffectiveTheme();
    const [content, setContent] = useState("");
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!isOpen) return;

        setLoading(true);
        setContent("");

        let fetchPromise: Promise<string>;
        if (reportId != null) {
            fetchPromise = fetchReportExecutionLog(reportId);
        } else if (activityId != null) {
            fetchPromise = fetchActivityLogDetails(activityId);
        } else if (projectId != null && taskId != null) {
            fetchPromise = fetchTaskExecutionLog(projectId, taskId);
        } else {
            setContent("No log source specified.");
            setLoading(false);
            return;
        }

        fetchPromise
            .then(setContent)
            .catch((err) => setContent("Error loading log: " + err.message))
            .finally(() => setLoading(false));
    }, [isOpen, projectId, taskId, activityId, reportId]);

    const handleClose = () => {
        onClose();
        setContent("");
    };

    const title = reportId != null
        ? `Execution Log — Report #${reportId}`
        : activityId != null
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
