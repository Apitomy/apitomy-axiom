import { useState, useEffect, useCallback, useMemo } from "react";
import {
    Button, EmptyState, EmptyStateBody,
    Flex, FlexItem, Label, Modal, ModalBody,
    ModalFooter, ModalHeader, Form, FormGroup,
    FormSelect, FormSelectOption,
} from "@patternfly/react-core";
import { WorkflowViewer } from "@apitomy/flow-ui";
import type { Workflow, WorkflowInstance } from "@apitomy/flow-ui";
import { useEffectiveTheme } from "../hooks/useTheme";
import { ConfirmDeleteModal } from "./ConfirmDeleteModal";
import {
    type WorkflowDefinition, type WorkflowInstanceInfo,
    fetchWorkflowDefinitions, triggerWorkflow,
    getWorkflowInstance, cancelWorkflow,
} from "../config/api";

interface WorkflowTabProps {
    projectId: number;
    hasWorkflowInstance: boolean;
    onRefresh: () => void;
}

export function WorkflowTab({
    projectId, hasWorkflowInstance, onRefresh,
}: WorkflowTabProps) {
    const effectiveTheme = useEffectiveTheme();
    const [instance, setInstance] =
        useState<WorkflowInstanceInfo | null>(null);
    const [loading, setLoading] = useState(true);
    const [isTriggerOpen, setIsTriggerOpen] = useState(false);
    const [isCancelOpen, setIsCancelOpen] = useState(false);
    const [definitions, setDefinitions] =
        useState<WorkflowDefinition[]>([]);
    const [selectedDefId, setSelectedDefId] = useState("");
    const [submitting, setSubmitting] = useState(false);

    const loadInstance = useCallback(() => {
        if (!hasWorkflowInstance) {
            setInstance(null);
            setLoading(false);
            return;
        }
        setLoading(true);
        getWorkflowInstance(projectId)
            .then(setInstance)
            .catch(() => setInstance(null))
            .finally(() => setLoading(false));
    }, [projectId, hasWorkflowInstance]);

    useEffect(() => {
        loadInstance();
    }, [loadInstance]);

    useEffect(() => {
        const eventSource = new EventSource("/api/v1/sse");
        const handler = (event: MessageEvent) => {
            try {
                const data = JSON.parse(event.data);
                if (data.projectId === projectId) {
                    loadInstance();
                    onRefresh();
                }
            } catch {
                // ignore
            }
        };
        eventSource.addEventListener(
            "workflow-updated", handler);
        return () => {
            eventSource.removeEventListener(
                "workflow-updated", handler);
            eventSource.close();
        };
    }, [projectId, loadInstance, onRefresh]);

    const openTriggerModal = useCallback(() => {
        fetchWorkflowDefinitions(1, 1000)
            .then((results) => {
                const published = results.items.filter(
                    (d) => d.currentVersion != null);
                setDefinitions(published);
                if (published.length > 0) {
                    setSelectedDefId(String(published[0].id));
                }
                setIsTriggerOpen(true);
            })
            .catch(console.error);
    }, []);

    const handleTrigger = useCallback(() => {
        if (!selectedDefId) return;
        setSubmitting(true);
        triggerWorkflow(projectId, {
            workflowDefinitionId: Number(selectedDefId),
        })
            .then(() => {
                setIsTriggerOpen(false);
                onRefresh();
                loadInstance();
            })
            .catch(console.error)
            .finally(() => setSubmitting(false));
    }, [projectId, selectedDefId, onRefresh, loadInstance]);

    const handleCancel = useCallback(() => {
        cancelWorkflow(projectId)
            .then(() => {
                setIsCancelOpen(false);
                onRefresh();
                loadInstance();
            })
            .catch(console.error);
    }, [projectId, onRefresh, loadInstance]);

    const workflowContent = useMemo<Workflow | null>(() => {
        if (!instance?.workflowContent) return null;
        return instance.workflowContent as Workflow;
    }, [instance]);

    const viewerInstance = useMemo<WorkflowInstance | null>(() => {
        if (!instance) return null;
        return {
            id: String(instance.id),
            workflowId: String(instance.definitionId),
            currentNodeId: instance.currentNodeId || "",
            status: instance.status as any,
            context: instance.context || {},
            history: (instance.history || []).map((h) => ({
                nodeId: h.nodeId,
                nodeName: h.nodeName,
                edgeId: "",
                edgeCondition: "",
                enteredOn: h.enteredOn,
                completedOn: h.completedOn || "",
                output: h.output || {},
            })),
            failureReason: instance.failureReason,
            createdOn: instance.startedOn,
            updatedOn: instance.completedOn || instance.startedOn,
        } as WorkflowInstance;
    }, [instance]);

    if (loading) {
        return <EmptyState><EmptyStateBody>
            Loading...
        </EmptyStateBody></EmptyState>;
    }

    if (!instance) {
        return (
            <>
                <EmptyState>
                    <EmptyStateBody>
                        No workflow is running on this project.
                    </EmptyStateBody>
                    <Button variant="primary"
                        onClick={openTriggerModal}>
                        Run Workflow
                    </Button>
                </EmptyState>

                <Modal isOpen={isTriggerOpen}
                    onClose={() => setIsTriggerOpen(false)}
                    variant="medium">
                    <ModalHeader title="Run Workflow" />
                    <ModalBody>
                        {definitions.length === 0 ? (
                            <EmptyState>
                                <EmptyStateBody>
                                    No published workflow definitions
                                    available.
                                </EmptyStateBody>
                            </EmptyState>
                        ) : (
                            <Form>
                                <FormGroup label="Workflow Definition"
                                    isRequired fieldId="wf-def">
                                    <FormSelect id="wf-def"
                                        value={selectedDefId}
                                        onChange={(_e, v) =>
                                            setSelectedDefId(v)}>
                                        {definitions.map((d) => (
                                            <FormSelectOption
                                                key={d.id}
                                                value={String(d.id)}
                                                label={`${d.name} (v${d.currentVersion})`}
                                            />
                                        ))}
                                    </FormSelect>
                                </FormGroup>
                            </Form>
                        )}
                    </ModalBody>
                    <ModalFooter>
                        <Button variant="primary"
                            onClick={handleTrigger}
                            isDisabled={
                                !selectedDefId
                                || definitions.length === 0
                                || submitting}
                            isLoading={submitting}>
                            Run Workflow
                        </Button>
                        <Button variant="link"
                            onClick={
                                () => setIsTriggerOpen(false)}>
                            Cancel
                        </Button>
                    </ModalFooter>
                </Modal>
            </>
        );
    }

    const isActive = instance.status === "running"
        || instance.status === "waiting";

    return (
        <>
            <Flex justifyContent={{
                default: "justifyContentSpaceBetween" }}
                alignItems={{
                    default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Flex alignItems={{
                        default: "alignItemsCenter" }}
                        spaceItems={{
                            default: "spaceItemsSm" }}>
                        <FlexItem>
                            <strong>
                                {instance.definitionName}
                            </strong>
                            {" v"}
                            {instance.definitionVersion}
                        </FlexItem>
                        <FlexItem>
                            <Label color={
                                instance.status === "completed"
                                    ? "green"
                                    : instance.status === "failed"
                                        ? "red"
                                        : instance.status === "cancelled"
                                            ? "grey"
                                            : "blue"}>
                                {instance.status}
                            </Label>
                        </FlexItem>
                        {instance.currentNodeName && isActive && (
                            <FlexItem>
                                <Label color="teal">
                                    {instance.currentNodeName}
                                </Label>
                            </FlexItem>
                        )}
                    </Flex>
                </FlexItem>
                {isActive && (
                    <FlexItem>
                        <Button variant="danger"
                            onClick={() => setIsCancelOpen(true)}>
                            Cancel Workflow
                        </Button>
                    </FlexItem>
                )}
            </Flex>

            {instance.failureReason && (
                <div style={{
                    padding: "8px 16px",
                    marginBottom: "16px",
                    backgroundColor:
                        "var(--pf-t--global--color--status--danger--default)",
                    color: "white",
                    borderRadius: "4px" }}>
                    {instance.failureReason}
                </div>
            )}

            {workflowContent && viewerInstance && (
                <div style={{ height: "500px" }}>
                    <WorkflowViewer
                        workflow={workflowContent}
                        instance={viewerInstance}
                        theme={effectiveTheme === "dark"
                            ? "dark" : "light"}
                    />
                </div>
            )}

            <ConfirmDeleteModal
                isOpen={isCancelOpen}
                title="Cancel Workflow"
                onConfirm={handleCancel}
                onCancel={() => setIsCancelOpen(false)}
                confirmLabel="Cancel Workflow">
                Are you sure you want to cancel this workflow?
                Any in-progress tasks will be stopped.
            </ConfirmDeleteModal>
        </>
    );
}
