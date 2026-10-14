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
    TextArea,
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
 * Parses labels typed as `key:value, key:value`.
 *
 * @param raw the typed text
 * @returns the labels, or null when an entry is not of the form `key:value`
 */
export function parseLabels(raw: string): Record<string, string> | null {
    const labels: Record<string, string> = {};
    const entries: string[] = raw.split(",").map((e) => e.trim()).filter((e) => e.length > 0);
    for (const entry of entries) {
        const idx: number = entry.indexOf(":");
        if (idx <= 0 || idx === entry.length - 1) {
            return null;
        }
        labels[entry.substring(0, idx).trim()] = entry.substring(idx + 1).trim();
    }
    return labels;
}

/**
 * Parses inputs typed as a JSON object.
 *
 * @param raw the typed text
 * @returns the inputs (empty when blank), or null when the text is not a JSON object
 */
export function parseInputs(raw: string): Record<string, unknown> | null {
    if (!raw.trim()) {
        return {};
    }
    try {
        const value: unknown = JSON.parse(raw);
        if (value && typeof value === "object" && !Array.isArray(value)) {
            return value as Record<string, unknown>;
        }
    } catch {
        // fall through
    }
    return null;
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
    const [inputsText, setInputsText] = useState<string>("");
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState<boolean>(false);

    useEffect(() => {
        if (!isOpen) {
            return;
        }
        setSelectedId("");
        setTitle("");
        setLabelsText("");
        setInputsText("");
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
    const inputs = parseInputs(inputsText);
    const selectedSuggestion = suggestions.find((s) => String(s.workflowDefinitionId) === selectedId);
    const canRun: boolean = !!selectedId && labels !== null && inputs !== null && !submitting;

    const run = () => {
        if (!canRun || labels === null || inputs === null) {
            return;
        }
        const body: TriggerStreamEventWorkflowRequest = { workflowDefinitionId: Number(selectedId) };
        if (title.trim()) body.title = title.trim();
        if (Object.keys(labels).length > 0) body.labels = labels;
        if (Object.keys(inputs).length > 0) body.inputs = inputs;
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
                        <TextInput id="run-wf-event-labels" value={labelsText} placeholder="key:value, key:value"
                            validated={labels === null ? "error" : "default"}
                            onChange={(_e, v) => setLabelsText(v)} />
                        {labels === null && (
                            <FormHelperText><HelperText><HelperTextItem variant="error">
                                Labels must be key:value pairs separated by commas.
                            </HelperTextItem></HelperText></FormHelperText>
                        )}
                    </FormGroup>
                    <FormGroup label="Inputs" fieldId="run-wf-event-inputs">
                        <TextArea id="run-wf-event-inputs" value={inputsText} placeholder="{ }" rows={5}
                            validated={inputs === null ? "error" : "default"}
                            onChange={(_e, v) => setInputsText(v)} />
                        {inputs === null && (
                            <FormHelperText><HelperText><HelperTextItem variant="error">
                                Inputs must be a JSON object.
                            </HelperTextItem></HelperText></FormHelperText>
                        )}
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
