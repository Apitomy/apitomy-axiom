import { useEffect, useState } from "react";
import { Alert, Button, Label, Tooltip } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import {
    type CompositionItem, type CompositionRevision, listRevisions, restoreRevision,
} from "../../config/compositions";

/** Props for {@link RevisionList}. */
export interface RevisionListProps {
    compositionId: number;
    item: CompositionItem;
    onRestored(item: CompositionItem): void;
}

/**
 * Lists an item's revisions, newest first, with a Restore action per revision.
 */
export function RevisionList({ compositionId, item, onRestored }: RevisionListProps) {
    const [revisions, setRevisions] = useState<CompositionRevision[]>([]);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled: boolean = false;
        listRevisions(compositionId, item.id)
            .then((r: CompositionRevision[]) => {
                if (!cancelled) {
                    setRevisions([...r].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id));
                }
            })
            .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
        return () => {
            cancelled = true;
        };
    }, [compositionId, item.id, item.contentRevision]);

    const restore = (revisionId: number) => {
        restoreRevision(compositionId, item.id, revisionId)
            .then((restored: CompositionItem) => onRestored(restored))
            .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
    };

    return (
        <div className="axiom-workbench__revisions">
            {error && <Alert isInline variant="danger" title={error} />}
            <Table aria-label={`Revisions of ${item.name}`} variant="compact">
                <Thead>
                    <Tr>
                        <Th>Author</Th>
                        <Th>Turn</Th>
                        <Th>Time</Th>
                        <Th screenReaderText="Actions" />
                    </Tr>
                </Thead>
                <Tbody>
                    {revisions.map((r: CompositionRevision) => {
                        const current: boolean = r.contentRevision === item.contentRevision;
                        return (
                            <Tr key={r.id}>
                                <Td dataLabel="Author">
                                    <Label isCompact color={r.author === "ai" ? "purple" : "blue"}>
                                        {r.author === "ai" ? "AI" : "You"}
                                    </Label>
                                    {current && <> <Label isCompact color="green">Current</Label></>}
                                </Td>
                                <Td dataLabel="Turn">
                                    {r.turnId ? (
                                        <Tooltip content={r.turnId}>
                                            <code>{r.turnId.substring(0, 8)}</code>
                                        </Tooltip>
                                    ) : "—"}
                                </Td>
                                <Td dataLabel="Time">{new Date(r.createdAt).toLocaleString()}</Td>
                                <Td isActionCell>
                                    <Button variant="link" isInline aria-label={`Restore revision ${r.id}`}
                                        isDisabled={current} onClick={() => restore(r.id)}>Restore</Button>
                                </Td>
                            </Tr>
                        );
                    })}
                </Tbody>
            </Table>
        </div>
    );
}
