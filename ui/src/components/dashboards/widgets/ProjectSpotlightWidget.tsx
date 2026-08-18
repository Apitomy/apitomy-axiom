import { useState, useEffect } from "react";
import { Flex, FlexItem, Label, Title } from "@patternfly/react-core";
import { type Project, type Task, fetchProject, fetchProjectTasks } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

function stripMarkdown(md: string): string {
    const plain = md
        .replace(/^#{1,6}\s+/gm, "")
        .replace(/\*\*(.+?)\*\*/g, "$1")
        .replace(/\*(.+?)\*/g, "$1")
        .replace(/`(.+?)`/g, "$1")
        .replace(/\[(.+?)\]\(.+?\)/g, "$1")
        .replace(/^[-*+]\s+/gm, "")
        .replace(/\n+/g, " ")
        .trim();
    return plain.length > 150 ? plain.slice(0, 147) + "..." : plain;
}

function ProjectSpotlightWidget({ config }: WidgetProps) {
    const [project, setProject] = useState<Project | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [error, setError] = useState(false);
    const projectId = Number(config.projectId);

    useEffect(() => {
        if (!projectId) return;
        let cancelled = false;
        Promise.all([fetchProject(projectId), fetchProjectTasks(projectId)])
            .then(([p, t]) => { if (!cancelled) { setProject(p); setTasks(t); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [projectId]);

    if (!projectId) {
        return <p style={{ padding: "16px" }}>Configure a project ID in widget settings.</p>;
    }
    if (error) return <WidgetError />;
    if (!project) {
        return <p style={{ padding: "16px" }}>Loading...</p>;
    }

    const tasksByStatus: Record<string, number> = {};
    for (const t of tasks) {
        tasksByStatus[t.status] = (tasksByStatus[t.status] || 0) + 1;
    }

    return (
        <div style={{ padding: "8px" }}>
            <Title headingLevel="h4" size="md">{project.name}</Title>
            <Flex gap={{ default: "gapSm" }} style={{ marginTop: "8px" }}>
                <FlexItem>
                    <Label isCompact color={
                        project.status === "InProgress" ? "green" :
                        project.status === "Idle" ? "orange" : "blue"
                    }>{project.status}</Label>
                </FlexItem>
                <FlexItem>
                    <Label isCompact>{project.issueRef}</Label>
                </FlexItem>
            </Flex>
            {project.body && (
                <p style={{ marginTop: "8px", fontSize: "0.9em",
                            color: "var(--pf-t--global--color--200)" }}>
                    {stripMarkdown(project.body)}
                </p>
            )}
            <div style={{ marginTop: "12px" }}>
                <strong>Tasks ({tasks.length})</strong>
                <Flex gap={{ default: "gapSm" }} style={{ marginTop: "4px" }}>
                    {Object.entries(tasksByStatus).map(([status, count]) => (
                        <FlexItem key={status}>
                            <Label isCompact>{status}: {count}</Label>
                        </FlexItem>
                    ))}
                </Flex>
            </div>
        </div>
    );
}

registerWidget({
    type: "project-spotlight",
    name: "Project Spotlight",
    description: "Single-project deep view with status, task breakdown, and details.",
    category: "Projects",
    defaultSize: { w: 4, h: 4 },
    minSize: { w: 3, h: 3 },
    configSchema: [
        { key: "projectId", label: "Project ID", type: "number", default: 0 },
    ],
    component: ProjectSpotlightWidget,
});
