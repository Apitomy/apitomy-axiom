import { useEffect, useMemo, useState } from "react";
import {
    Alert,
    Button,
    Form,
    FormGroup,
    FormHelperText,
    FormSelect,
    FormSelectOption,
    FormSelectOptionGroup,
    HelperText,
    HelperTextItem,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    TextInput,
} from "@patternfly/react-core";
import {
    type StreamEventWorkflowSuggestion,
    type TriggerStreamEventWorkflowRequest,
    type WorkflowDefinition,
    type WorkflowInstanceInfo,
    fetchStreamEventWorkflowSuggestions,
    fetchWorkflowDefinitions,
    triggerStreamEventWorkflow,
} from "../config/api";

/**
 * Parses labels typed as a comma-separated list: trims them, drops empty ones and removes duplicates.
 *
 * @param raw the typed text
 * @returns the labels
 */
export function parseLabels(raw: string): string[] {
    const entries: string[] = raw.split(",").map((e) => e.trim()).filter((e) => e.length > 0);
    return Array.from(new Set(entries));
}

/** Props of {@link RunWorkflowFromEventModal}. */
export interface RunWorkflowFromEventModalProps {
    eventId: string;
    isOpen: boolean;
    onClose: () => void;
    /** Called with the started run after a successful Run. */
    onStarted: (run: WorkflowInstanceInfo) => void;
}

/** Modal that manually runs a workflow on a stream event's subject, with the event in `context.event`. */
export function RunWorkflowFromEventModal({ eventId, isOpen, onClose, onStarted }: RunWorkflowFromEventModalProps) {
    const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([]);
    const [suggestions, setSuggestions] = useState<StreamEventWorkflowSuggestion[]>([]);
    const [selectedId, setSelectedId] = useState<string>("");
    const [title, setTitle] = useState<string>("");
    const [labelsText, setLabelsText] = useState<string>("");
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState<boolean>(false);

    useEffect(() => {
        if (!isOpen) {
            return;
        }
        setSelectedId("");
        setTitle("");
        setLabelsText("");
        setError(null);
        fetchWorkflowDefinitions(1, 1000)
            .then((r) => setDefinitions(r.items.filter((d) => d.currentVersion != null)))
            .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
        fetchStreamEventWorkflowSuggestions(eventId)
            .then(setSuggestions)
            .catch(() => setSuggestions([]));
    }, [isOpen, eventId]);

    const suggested = useMemo(() => {
        const published: Set<number> = new Set(definitions.map((d) => d.id));
        const seen: Set<number> = new Set();
        return suggestions.filter((s) => {
            if (!published.has(s.workflowDefinitionId) || seen.has(s.workflowDefinitionId)) {
                return false;
            }
            seen.add(s.workflowDefinitionId);
            return true;
        });
    }, [suggestions, definitions]);

    const allSorted: WorkflowDefinition[] = useMemo(
        () => [...definitions].sort((a, b) => a.name.localeCompare(b.name)), [definitions]);

    const labels = parseLabels(labelsText);
    const selectedSuggestion = suggestions.find((s) => String(s.workflowDefinitionId) === selectedId);
    const canRun: boolean = !!selectedId && !submitting;

    const run = () => {
        if (!canRun) {
            return;
        }
        const body: TriggerStreamEventWorkflowRequest = { workflowDefinitionId: Number(selectedId) };
        if (title.trim()) body.title = title.trim();
        if (labels.length > 0) body.labels = labels;
        setSubmitting(true);
        setError(null);
        triggerStreamEventWorkflow(eventId, body)
            .then((r) => onStarted(r))
            .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
            .finally(() => setSubmitting(false));
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="medium" elementToFocus="#run-wf-event-def"
            aria-labelledby="run-wf-event-title">
            <ModalHeader title="Run workflow" labelId="run-wf-event-title"
                description="The run starts on the event's subject, with the event in context.event." />
            <ModalBody>
                {error && (
                    <Alert variant="danger" isInline title="Failed to run workflow" style={{ marginBottom: "16px" }}>
                        {error}
                    </Alert>
                )}
                <Form onSubmit={(e) => { e.preventDefault(); run(); }}>
                    <FormGroup label="Workflow" isRequired fieldId="run-wf-event-def">
                        <FormSelect id="run-wf-event-def" value={selectedId}
                            onChange={(_e, v) => setSelectedId(v)}>
                            <FormSelectOption value="" label="Select a workflow" isPlaceholder />
                            {suggested.length > 0 && (
                                <FormSelectOptionGroup label="Suggested">
                                    {suggested.map((s) => (
                                        <FormSelectOption key={`s-${s.workflowDefinitionId}`}
                                            value={String(s.workflowDefinitionId)}
                                            label={`${s.workflowName} — used by subscription ${s.subscriptionName}`} />
                                    ))}
                                </FormSelectOptionGroup>
                            )}
                            <FormSelectOptionGroup label="All workflows">
                                {allSorted.map((d) => (
                                    <FormSelectOption key={d.id} value={String(d.id)} label={d.name} />
                                ))}
                            </FormSelectOptionGroup>
                        </FormSelect>
                        {selectedSuggestion && (
                            <FormHelperText><HelperText><HelperTextItem>
                                Used by subscription {selectedSuggestion.subscriptionName}
                            </HelperTextItem></HelperText></FormHelperText>
                        )}
                    </FormGroup>
                    <FormGroup label="Title" fieldId="run-wf-event-title-input">
                        <TextInput id="run-wf-event-title-input" value={title} onChange={(_e, v) => setTitle(v)} />
                    </FormGroup>
                    <FormGroup label="Labels" fieldId="run-wf-event-labels">
                        <TextInput id="run-wf-event-labels" value={labelsText} placeholder="label, label"
                            onChange={(_e, v) => setLabelsText(v)} />
                        <FormHelperText><HelperText><HelperTextItem>
                            Separate labels with commas. The run is also labeled trigger:manual.
                        </HelperTextItem></HelperText></FormHelperText>
                    </FormGroup>
                </Form>
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={run} isDisabled={!canRun} isLoading={submitting}>Run</Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
