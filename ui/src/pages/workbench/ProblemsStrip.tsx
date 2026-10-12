import { ExpandableSection } from "@patternfly/react-core";
import { useState } from "react";
import type { ConfigProblem } from "../../config/compositions";

/** Props for {@link ProblemsStrip}. */
export interface ProblemsStripProps {
    /** Problems of the active item. */
    problems: ConfigProblem[];
}

/**
 * Collapsible strip listing the active item's problems as `severity · path · message`.
 */
export function ProblemsStrip({ problems }: ProblemsStripProps) {
    const [expanded, setExpanded] = useState<boolean>(false);
    return (
        <ExpandableSection className="axiom-workbench__problems" toggleText={`Problems (${problems.length})`}
            isExpanded={expanded} onToggle={(_e, open) => setExpanded(open)}>
            {problems.length === 0 ? <div>No problems</div> : (
                <ul>
                    {problems.map((p, i) => (
                        <li key={i}>{[p.severity, p.path, p.message].filter(Boolean).join(" · ")}</li>
                    ))}
                </ul>
            )}
        </ExpandableSection>
    );
}
