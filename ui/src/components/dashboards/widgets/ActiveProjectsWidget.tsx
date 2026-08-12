import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";
import BugIcon from "@patternfly/react-icons/dist/esm/icons/bug-icon";
import GithubIcon from "@patternfly/react-icons/dist/esm/icons/github-icon";
import JiraIcon from "@patternfly/react-icons/dist/esm/icons/jira-icon";
import { type Project, fetchProjects } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    Created: "blue", InProgress: "green", Idle: "orange", Completed: "grey", Failed: "red",
};

function ActiveProjectsWidget({ config, labels }: WidgetProps) {
    const navigate = useNavigate();
    const [projects, setProjects] = useState<Project[]>([]);
    const [error, setError] = useState(false);
    const maxRows = Number(config.maxRows) || 8;

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchProjects(1, maxRows, undefined, "Created,InProgress,Idle", labelsParam)
            .then(result => { if (!cancelled) setProjects(result.items); })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, maxRows]);

    if (error) return <WidgetError />;

    return (
        <Table aria-label="Active Projects" variant="compact" isStickyHeader>
            <Thead><Tr>
                <Th>Name</Th><Th>Status</Th><Th>Issue</Th>
            </Tr></Thead>
            <Tbody>
                {projects.map(p => (
                    <Tr key={p.id} isClickable onRowClick={() => navigate(`/projects/${p.id}`)}>
                        <Td>
                            {p.issueSource === "github" && <GithubIcon style={{ marginRight: 6 }} />}
                            {p.issueSource === "jira" && <JiraIcon style={{ marginRight: 6 }} />}
                            {p.type === "issue" && <BugIcon style={{ marginRight: 6 }} />}
                            {p.type === "pull-request" && <CodeBranchIcon style={{ marginRight: 6 }} />}
                            {p.name}
                        </Td>
                        <Td>
                            <Label isCompact color={STATUS_COLORS[p.status] || "grey"}>
                                {p.status}
                            </Label>
                        </Td>
                        <Td>{p.issueRef || "—"}</Td>
                    </Tr>
                ))}
                {projects.length === 0 && (
                    <Tr><Td colSpan={3}>No active projects.</Td></Tr>
                )}
            </Tbody>
        </Table>
    );
}

registerWidget({
    type: "active-projects",
    name: "Active Projects",
    description: "Compact table of non-completed projects with status and issue reference.",
    category: "Projects",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "maxRows", label: "Max Rows", type: "number", default: 8 },
    ],
    component: ActiveProjectsWidget,
});
