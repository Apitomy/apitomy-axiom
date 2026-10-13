import { useEffect, useState } from "react";
import { Button } from "@patternfly/react-core";
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
            {error && <div role="alert">{error}</div>}
            <ul>
                {revisions.map((r: CompositionRevision) => (
                    <li key={r.id}>
                        <span>{r.author === "ai" ? "AI" : "You"}</span>
                        {r.turnId && <span title={r.turnId}> · turn {r.turnId.substring(0, 8)}</span>}
                        <span> · {new Date(r.createdAt).toLocaleString()}</span>{" "}
                        <Button variant="link" isInline aria-label={`Restore revision ${r.id}`}
                            onClick={() => restore(r.id)}>Restore</Button>
                    </li>
                ))}
            </ul>
        </div>
    );
}
