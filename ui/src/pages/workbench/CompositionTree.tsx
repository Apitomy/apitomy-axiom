import { useEffect, useState } from "react";
import {
    Button, Form, FormGroup, FormSelect, FormSelectOption, Label, Modal, ModalBody, ModalFooter, ModalHeader,
    Radio, SimpleList, SimpleListGroup, SimpleListItem, TextInput, Tooltip,
} from "@patternfly/react-core";
import {
    CheckCircleIcon, ExclamationCircleIcon, ExclamationTriangleIcon, LockIcon, PlusCircleIcon,
} from "@patternfly/react-icons";
import type { Composition, CompositionItem, CompositionItemType, ConfigProblem } from "../../config/compositions";
import { fetchActionTypes, fetchTools } from "../../config/api";
import { isAiTouchedInTurn, isLockedForUser, itemStatus, type ItemStatus } from "./workbenchLogic";
import { RevisionList } from "./RevisionList";

/** Item types the user can add from the tree. */
export type AddableType = Exclude<CompositionItemType, "workflow">;

/** The add request produced by the add dialog. */
export interface AddRequest {
    type: AddableType;
    name?: string;
    existingItemId?: number;
}

/** Props for {@link CompositionTree}. */
export interface CompositionTreeProps {
    composition: Composition;
    items: CompositionItem[];
    problems: Record<number, ConfigProblem[]>;
    lastCompletedTurnId: string | undefined;
    selectedItemId: number | undefined;
    onOpen(itemId: number): void;
    /** Called with the dialog result; the add dialog is opened by the tree itself. */
    onAdd(request: AddRequest): void;
    onRestored(item: CompositionItem): void;
}

const GROUPS: { type: CompositionItemType; title: string; addLabel?: string }[] = [
    { type: "workflow", title: "Workflow" },
    { type: "action-type", title: "Action types", addLabel: "Add action type" },
    { type: "tool", title: "Tools", addLabel: "Add tool" },
];

function StatusIcon({ status }: { status: ItemStatus }) {
    switch (status) {
        case "valid":
            return <CheckCircleIcon aria-label="valid" color="var(--pf-t--global--icon--color--status--success--default)" />;
        case "warnings":
            return <ExclamationTriangleIcon aria-label="warnings" color="var(--pf-t--global--icon--color--status--warning--default)" />;
        case "errors":
            return <ExclamationCircleIcon aria-label="errors" color="var(--pf-t--global--icon--color--status--danger--default)" />;
        default:
            return null;
    }
}

/**
 * The composition's items grouped by type, with status, AI and unpublished markers, an add dialog and the
 * selected item's revisions.
 */
export function CompositionTree(props: CompositionTreeProps) {
    const { composition, items, problems, lastCompletedTurnId, onOpen, onAdd, onRestored } = props;
    const [selected, setSelected] = useState<number | undefined>(props.selectedItemId);
    const [adding, setAdding] = useState<AddableType | null>(null);

    useEffect(() => {
        if (props.selectedItemId !== undefined) {
            setSelected(props.selectedItemId);
        }
    }, [props.selectedItemId]);

    const selectedItem: CompositionItem | undefined = items.find((i) => i.id === selected);

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
                                    <StatusIcon status={itemStatus(problems[item.id])} />
                                    {isLockedForUser(item, composition) && <LockIcon aria-label="locked" />}
                                    {isAiTouchedInTurn(item, lastCompletedTurnId) && (
                                        <Label isCompact color="purple">AI</Label>
                                    )}
                                    {item.changed && (
                                        <Tooltip content="Unpublished changes">
                                            <span className="axiom-workbench__dot" aria-label="Unpublished changes">●</span>
                                        </Tooltip>
                                    )}
                                </span>
                            </SimpleListItem>
                        ))}
                        {g.addLabel && (
                            <li className="axiom-workbench__add-row">
                                <Button variant="link" size="sm" icon={<PlusCircleIcon />}
                                    aria-label={`Add ${g.title.toLowerCase()}`}
                                    onClick={() => setAdding(g.type as AddableType)}>{g.addLabel}</Button>
                            </li>
                        )}
                    </SimpleListGroup>
                ))}
            </SimpleList>
            {selectedItem && (
                <RevisionList compositionId={composition.id} item={selectedItem} onRestored={onRestored} />
            )}
            {adding && (
                <AddItemDialog type={adding} onClose={() => setAdding(null)}
                    onSubmit={(r: AddRequest) => {
                        setAdding(null);
                        onAdd(r);
                    }} />
            )}
        </div>
    );
}

interface AddItemDialogProps {
    type: AddableType;
    onSubmit(request: AddRequest): void;
    onClose(): void;
}

function AddItemDialog({ type, onSubmit, onClose }: AddItemDialogProps) {
    const [mode, setMode] = useState<"new" | "existing">("new");
    const [name, setName] = useState<string>("");
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
        ? { type, name: name.trim() }
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
                        <FormGroup label="Name" fieldId="add-name">
                            <TextInput id="add-name" value={name} onChange={(_e, v) => setName(v)} />
                        </FormGroup>
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
