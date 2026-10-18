import { useState, useEffect } from "react";
import { Flex, FlexItem, Label, Title } from "@patternfly/react-core";
import { type Subject, type Task, fetchSubject, fetchSubjectTasks } from "../../../config/api";
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

function SubjectSpotlightWidget({ config }: WidgetProps) {
    const [subject, setSubject] = useState<Subject | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [error, setError] = useState(false);
    const subjectId = Number(config.subjectId);

    useEffect(() => {
        if (!subjectId) return;
        let cancelled = false;
        Promise.all([fetchSubject(subjectId), fetchSubjectTasks(subjectId)])
            .then(([p, t]) => { if (!cancelled) { setSubject(p); setTasks(t); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [subjectId]);

    if (!subjectId) {
        return <p style={{ padding: "16px" }}>Configure a subject ID in widget settings.</p>;
    }
    if (error) return <WidgetError />;
    if (!subject) {
        return <p style={{ padding: "16px" }}>Loading...</p>;
    }

    const tasksByStatus: Record<string, number> = {};
    for (const t of tasks) {
        tasksByStatus[t.status] = (tasksByStatus[t.status] || 0) + 1;
    }

    return (
        <div style={{ padding: "8px" }}>
            <Title headingLevel="h4" size="md">{subject.name}</Title>
            <Flex gap={{ default: "gapSm" }} style={{ marginTop: "8px" }}>
                <FlexItem>
                    <Label isCompact color={subject.active ? "green" : "grey"}>
                        {subject.active ? "Active" : "Inactive"}
                    </Label>
                </FlexItem>
                <FlexItem>
                    <Label isCompact>{subject.ref}</Label>
                </FlexItem>
            </Flex>
            {subject.body && (
                <p style={{ marginTop: "8px", fontSize: "0.9em",
                            color: "var(--pf-t--global--color--200)" }}>
                    {stripMarkdown(subject.body)}
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
    type: "subject-spotlight",
    name: "Subject Spotlight",
    description: "Single-subject deep view with status, task breakdown, and details.",
    category: "Subjects",
    scope: "subject-labels",
    defaultSize: { w: 4, h: 4 },
    minSize: { w: 3, h: 3 },
    configSchema: [
        { key: "subjectId", label: "Subject ID", type: "number", default: 0 },
    ],
    component: SubjectSpotlightWidget,
});
