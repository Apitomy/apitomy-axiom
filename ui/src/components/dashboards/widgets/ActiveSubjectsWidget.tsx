import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";
import BugIcon from "@patternfly/react-icons/dist/esm/icons/bug-icon";
import GithubIcon from "@patternfly/react-icons/dist/esm/icons/github-icon";
import JiraIcon from "@patternfly/react-icons/dist/esm/icons/jira-icon";
import { type Subject, fetchSubjects } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

function ActiveSubjectsWidget({ config, labels }: WidgetProps) {
    const navigate = useNavigate();
    const [subjects, setSubjects] = useState<Subject[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 8;

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchSubjects(1, maxRows, undefined, true, labelsParam)
            .then(result => { if (!cancelled) setSubjects(result.items); })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, maxRows]);

    if (error) return <WidgetError />;

    return (
        <Table aria-label="Active Subjects" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Name</Th><Th>Reference</Th>
            </Tr></Thead>
            <Tbody>
                {subjects.map(p => (
                    <Tr key={p.id} isClickable onRowClick={() => navigate(`/subjects/${p.id}`)}>
                        <Td>
                            {p.refSource === "github" && <GithubIcon style={{ marginRight: 6 }} />}
                            {p.refSource === "jira" && <JiraIcon style={{ marginRight: 6 }} />}
                            {p.type === "issue" && <BugIcon style={{ marginRight: 6 }} />}
                            {p.type === "pull-request" && <CodeBranchIcon style={{ marginRight: 6 }} />}
                            {p.name}
                        </Td>
                        <Td>{p.ref || "—"}</Td>
                    </Tr>
                ))}
                {subjects.length === 0 && (
                    <Tr><Td colSpan={2}>No active subjects.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "active-subjects",
    name: "Active Subjects",
    description: "Compact table of active subjects (with a running or waiting workflow run) and their issue reference.",
    category: "Subjects",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 8 },
    ],
    component: ActiveSubjectsWidget,
});
