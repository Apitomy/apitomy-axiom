import { useEffect, useState } from "react";
import {
    Button, Form, FormGroup, FormSelect, FormSelectOption, Label, Modal, ModalBody, ModalFooter, ModalHeader,
    Radio, SimpleList, SimpleListGroup, SimpleListItem, TextInput, Tooltip,
} from "@patternfly/react-core";
import {
    CheckCircleIcon, ExclamationCircleIcon, ExclamationTriangleIcon, LockIcon,
} from "@patternfly/react-icons";
import type {
    Composition, CompositionItem, CompositionItemType, ConfigProblem, ExecutionMode,
} from "../../config/compositions";
import { fetchActionTypes, fetchTools } from "../../config/api";
import { isAiTouchedInTurn, isLockedForUser, itemStatus, type ItemStatus } from "./workbenchLogic";

/** Item types the user can add from the tree. */
export type AddableType = Exclude<CompositionItemType, "workflow">;

/** The add request produced by the add dialog. */
export interface AddRequest {
    type: AddableType;
    name?: string;
    existingItemId?: number;
    /** Execution mode of a new action type. */
    executionMode?: ExecutionMode;
}

/** Choices for the execution mode of a new action type. */
const EXECUTION_MODES: { value: ExecutionMode; label: string }[] = [
    { value: "agent", label: "AI Agent — executed by an AI agent" },
    { value: "node", label: "Node.js — runs a JavaScript module" },
    { value: "script", label: "Script — executes a bash script" },
];

/** Props for {@link CompositionTree}. */
export interface CompositionTreeProps {
    composition: Composition;
    items: CompositionItem[];
    problems: Record<number, ConfigProblem[]>;
    lastCompletedTurnId: string | undefined;
    selectedItemId: number | undefined;
    onOpen(itemId: number): void;
}

const GROUPS: { type: CompositionItemType; title: string }[] = [
    { type: "workflow", title: "Workflow" },
    { type: "action-type", title: "Action types" },
    { type: "tool", title: "Tools" },
];

function countLabel(n: number, word: string): string {
    return `${n} ${word}${n === 1 ? "" : "s"}`;
}

/** Tooltip text for an item's validation status, with problem counts. */
export function statusTooltip(problems: ConfigProblem[] | undefined): string {
    const errors: number = (problems ?? []).filter((p) => p.severity === "error").length;
    const warnings: number = (problems ?? []).filter((p) => p.severity === "warning").length;
    if (errors > 0) {
        return warnings > 0
            ? `${countLabel(errors, "error")}, ${countLabel(warnings, "warning")}. Fix the errors before publishing.`
            : `${countLabel(errors, "error")}. Fix the errors before publishing.`;
    }
    if (warnings > 0) {
        return `${countLabel(warnings, "warning")}. Publishing is still allowed.`;
    }
    return "Valid";
}

function StatusIcon({ problems }: { problems: ConfigProblem[] | undefined }) {
    const status: ItemStatus = itemStatus(problems);
    let icon;
    switch (status) {
        case "valid":
            icon = <CheckCircleIcon aria-label="valid" color="var(--pf-t--global--icon--color--status--success--default)" />;
            break;
        case "warnings":
            icon = <ExclamationTriangleIcon aria-label="warnings" color="var(--pf-t--global--icon--color--status--warning--default)" />;
            break;
        case "errors":
            icon = <ExclamationCircleIcon aria-label="errors" color="var(--pf-t--global--icon--color--status--danger--default)" />;
            break;
        default:
            return null;
    }
    return (
        <Tooltip content={statusTooltip(problems)}>
            <span className="axiom-workbench__marker">{icon}</span>
        </Tooltip>
    );
}

/**
 * The composition's items grouped by type, with status, AI and unpublished markers, an add dialog and the
 * selected item's revisions.
 */
export function CompositionTree(props: CompositionTreeProps) {
    const { composition, items, problems, lastCompletedTurnId, onOpen } = props;
    const [selected, setSelected] = useState<number | undefined>(props.selectedItemId);

    useEffect(() => {
        if (props.selectedItemId !== undefined) {
            setSelected(props.selectedItemId);
        }
    }, [props.selectedItemId]);


    return (
        <div className="axiom-workbench__tree">
            <SimpleList isControlled={false} aria-label="Composition components">
                {GROUPS.map((g) => (
                    <SimpleListGroup key={g.type} id={`composition-group-${g.type}`} title={g.title}>
                        {items.filter((i) => i.type === g.type).map((item) => (
                            <SimpleListItem key={item.id} itemId={item.id} isActive={item.id === selected}
                                componentProps={{ "data-name": item.name }}
                                onClick={() => {
                                    setSelected(item.id);
                                    onOpen(item.id);
                                }}>
                                <span className="axiom-workbench__item">
                                    <span className="axiom-workbench__item-name">{item.name}</span>{" "}
                                    <span className="axiom-workbench__item-markers">
                                        <StatusIcon problems={problems[item.id]} />
                                        {isLockedForUser(item, composition) && (
                                            <Tooltip content="The AI is editing this item. It becomes editable when the turn ends.">
                                                <span className="axiom-workbench__marker"><LockIcon aria-label="locked" /></span>
                                            </Tooltip>
                                        )}
                                        {isAiTouchedInTurn(item, lastCompletedTurnId) && (
                                            <Tooltip content="Changed by the last AI turn">
                                                <Label isCompact color="purple">AI</Label>
                                            </Tooltip>
                                        )}
                                        {item.changed && (
                                            <Tooltip content="Unpublished changes">
                                                <span className="axiom-workbench__dot" aria-label="Unpublished changes">●</span>
                                            </Tooltip>
                                        )}
                                    </span>
                                </span>
                            </SimpleListItem>
                        ))}
                    </SimpleListGroup>
                ))}
            </SimpleList>
        </div>
    );
}

/** Props for {@link AddItemDialog}. */
export interface AddItemDialogProps {
    type: AddableType;
    onSubmit(request: AddRequest): void;
    onClose(): void;
}

/**
 * Dialog to add a new or existing action type or tool to the composition.
 */
export function AddItemDialog({ type, onSubmit, onClose }: AddItemDialogProps) {
    const [mode, setMode] = useState<"new" | "existing">("new");
    const [name, setName] = useState<string>("");
    const [executionMode, setExecutionMode] = useState<ExecutionMode>("agent");
    const [options, setOptions] = useState<{ id: number; name: string }[]>([]);
    const [existing, setExisting] = useState<string>("");
    const label: string = type === "tool" ? "tool" : "action type";

    useEffect(() => {
        const load = type === "tool" ? fetchTools(1, 1000) : fetchActionTypes(1, 1000);
        load.then((r) => {
            const opts = r.items.map((i: { id: number; name: string }) => ({ id: i.id, name: i.name }));
            setOptions(opts);
            if (opts.length > 0) {
                setExisting(String(opts[0].id));
            }
        }).catch(console.error);
    }, [type]);

    const valid: boolean = mode === "new" ? name.trim().length > 0 : existing !== "";
    const submit = () => onSubmit(mode === "new"
        ? (type === "action-type" ? { type, name: name.trim(), executionMode } : { type, name: name.trim() })
        : { type, existingItemId: Number(existing) });

    return (
        <Modal isOpen variant="small" onClose={onClose} aria-labelledby="add-item-title">
            <ModalHeader title={`Add ${label}`} labelId="add-item-title" />
            <ModalBody>
                <Form onSubmit={(e) => { e.preventDefault(); if (valid) { submit(); } }}>
                    <Radio id="add-new" name="add-mode" label="New" isChecked={mode === "new"}
                        onChange={() => setMode("new")} />
                    <Radio id="add-existing" name="add-mode" label="Existing" isChecked={mode === "existing"}
                        onChange={() => setMode("existing")} />
                    {mode === "new" ? (
                        <>
                            <FormGroup label="Name" fieldId="add-name">
                                <TextInput id="add-name" value={name} onChange={(_e, v) => setName(v)} />
                            </FormGroup>
                            {type === "action-type" && (
                                <FormGroup label="Execution mode" fieldId="add-execution-mode">
                                    <FormSelect id="add-execution-mode" value={executionMode}
                                        onChange={(_e, v) => setExecutionMode(v as ExecutionMode)}>
                                        {EXECUTION_MODES.map((m) => (
                                            <FormSelectOption key={m.value} value={m.value} label={m.label} />
                                        ))}
                                    </FormSelect>
                                </FormGroup>
                            )}
                        </>
                    ) : (
                        <FormGroup label={`Existing ${label}`} fieldId="add-existing-select">
                            <FormSelect id="add-existing-select" value={existing} onChange={(_e, v) => setExisting(v)}>
                                {options.map((o) => <FormSelectOption key={o.id} value={String(o.id)} label={o.name} />)}
                            </FormSelect>
                        </FormGroup>
                    )}
                </Form>
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" isDisabled={!valid} onClick={submit}>Add</Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
