import { useEffect, useMemo, useState } from "react";
import { Alert, Button, Checkbox, Modal, ModalBody, ModalFooter, ModalHeader } from "@patternfly/react-core";
import {
    ApiError, type CompositionItem, type ConfigProblem, fetchItem, publishComposition, type PublishResult,
} from "../../config/compositions";
import { defaultPublishChoice, itemStatus, type ItemStatus, publishSelection } from "./workbenchLogic";

/** Props for {@link PublishDialog}. */
export interface PublishDialogProps {
    compositionId: number;
    items: CompositionItem[];
    contents: Record<number, Record<string, unknown>>;
    problems: Record<number, ConfigProblem[]>;
    onPublished(result: PublishResult): void;
    onClose(): void;
}

/**
 * Lets the user choose which changed items to publish; dependencies are added automatically. Handles
 * conflicts (409, with Publish anyway) and blocking problems (422).
 */
export function PublishDialog({ compositionId, items, contents, problems, onPublished, onClose }: PublishDialogProps) {
    const changed: CompositionItem[] = useMemo(() => items.filter((i) => i.changed), [items]);
    const [loaded, setLoaded] = useState<Record<number, Record<string, unknown>>>({});
    const allContents = useMemo(() => ({ ...loaded, ...contents }), [loaded, contents]);
    const [chosen, setChosen] = useState<number[]>(() => {
        const statuses: Record<number, ItemStatus> = {};
        items.forEach((i) => { statuses[i.id] = itemStatus(problems[i.id]); });
        return defaultPublishChoice(items, statuses);
    });
    const [busy, setBusy] = useState<boolean>(false);
    const [conflict, setConflict] = useState<string | null>(null);
    const [blocking, setBlocking] = useState<Record<string, ConfigProblem[]> | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [result, setResult] = useState<PublishResult | null>(null);

    useEffect(() => {
        const missing: CompositionItem[] = changed.filter((i) => i.type !== "tool" && !contents[i.id] && !i.content);
        missing.forEach((i) => {
            fetchItem(compositionId, i.id)
                .then((full) => full.content && setLoaded((prev) => ({ ...prev, [i.id]: full.content! })))
                .catch(console.error);
        });
    }, [compositionId, changed, contents]);

    const selection: number[] = useMemo(() => publishSelection(items, allContents, chosen), [items, allContents, chosen]);
    const requiredBy = (id: number): string[] => chosen
        .filter((c) => c !== id && publishSelection(items, allContents, [c]).includes(id))
        .map((c) => items.find((i) => i.id === c)?.name ?? String(c));

    const toggle = (id: number, on: boolean) =>
        setChosen((prev) => (on ? [...prev, id] : prev.filter((c) => c !== id)));

    const publish = (force: boolean) => {
        setBusy(true);
        setConflict(null);
        setBlocking(null);
        setError(null);
        publishComposition(compositionId, selection, force)
            .then((r: PublishResult) => {
                setResult(r);
                onPublished(r);
            })
            .catch((e: unknown) => {
                if (e instanceof ApiError && e.status === 409) {
                    setConflict(e.message);
                } else if (e instanceof ApiError && e.status === 422) {
                    const body = e.body as { message?: string; problems?: Record<string, ConfigProblem[]> } | undefined;
                    setBlocking(body?.problems ?? {});
                    setError(body?.message ?? e.message);
                } else {
                    setError(e instanceof Error ? e.message : String(e));
                }
            })
            .finally(() => setBusy(false));
    };

    return (
        <Modal isOpen variant="medium" onClose={onClose} aria-labelledby="publish-title">
            <ModalHeader title="Publish" labelId="publish-title" />
            <ModalBody>
                {changed.length === 0 && !result && <p>No unpublished changes.</p>}
                {changed.map((item) => {
                    const deps: string[] = requiredBy(item.id);
                    const locked: boolean = deps.length > 0;
                    return (
                        <Checkbox key={item.id} id={`publish-${item.id}`} label={item.name}
                            description={locked ? `required by ${deps.join(", ")}` : item.type}
                            isChecked={selection.includes(item.id)} isDisabled={locked || !!result}
                            onChange={(_e, on) => toggle(item.id, on)} />
                    );
                })}
                {conflict && (
                    <Alert isInline variant="warning" title={conflict}>
                        <Button variant="secondary" isDisabled={busy} onClick={() => publish(true)}>Publish anyway</Button>
                    </Alert>
                )}
                {error && (
                    <Alert isInline variant="danger" title={error}>
                        {blocking && (
                            <ul>
                                {Object.entries(blocking).map(([key, list]) => (
                                    <li key={key}><strong>{key}</strong>
                                        <ul>{list.map((p, idx) => <li key={idx}>{p.message}</li>)}</ul>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </Alert>
                )}
                {result && <Alert isInline variant="success" title={`Created ${result.created}, updated ${result.updated}`} />}
            </ModalBody>
            <ModalFooter>
                {!result && (
                    <Button variant="primary" isDisabled={busy || selection.length === 0} onClick={() => publish(false)}>
                        Publish
                    </Button>
                )}
                <Button variant="link" onClick={onClose}>{result ? "Close" : "Cancel"}</Button>
            </ModalFooter>
        </Modal>
    );
}
