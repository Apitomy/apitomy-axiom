import { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { Flex, FlexItem, Label, Spinner, Title } from "@patternfly/react-core";
import {
    type Subject, type SubjectMetrics, type WorkflowRunSummary,
    fetchSubject, fetchSubjectMetrics, fetchWorkflowRuns,
} from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { SSE_EVENTS } from "../../../config/sseEvents";

/** Workflow run statuses shown as counts. */
export const RUN_STATUSES = ["running", "waiting", "completed", "failed", "cancelled"] as const;

const STATUS_COLORS: Record<string, "blue" | "orange" | "green" | "red" | "grey"> = {
    running: "blue", waiting: "orange", completed: "green", failed: "red", cancelled: "grey",
};

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

/**
 * Reads the configured subject id. Legacy dashboards stored `0` (or nothing) to mean "no subject".
 *
 * @param value the saved config value
 * @returns a positive subject id, or null when none is selected
 */
export function toSubjectId(value: unknown): number | null {
    const id = Number(value);
    return Number.isInteger(id) && id > 0 ? id : null;
}

interface SpotlightData {
    subject: Subject;
    counts: Record<string, number>;
    waiting: WorkflowRunSummary[];
    metrics: SubjectMetrics | null;
}

function SubjectSpotlightWidget({ config, refreshToken }: WidgetProps) {
    const [data, setData] = useState<SpotlightData | null>(null);
    const [state, setState] = useState<"loading" | "ok" | "not-found" | "error">("loading");
    const subjectId = toSubjectId(config.subjectId);

    useEffect(() => {
        if (subjectId == null) return;
        let cancelled = false;
        fetchSubject(subjectId)
            .then(async subject => {
                const countPromises = RUN_STATUSES.map(status =>
                    fetchWorkflowRuns(1, status === "waiting" ? 5 : 1, { subjectId, status })
                        .then(r => ({ status, total: r.totalCount, items: r.items })));
                const [results, metrics] = await Promise.all([
                    Promise.all(countPromises),
                    fetchSubjectMetrics(subjectId).catch(() => null),
                ]);
                const counts: Record<string, number> = {};
                results.forEach(r => { counts[r.status] = r.total; });
                const waiting = results.find(r => r.status === "waiting")?.items ?? [];
                if (!cancelled) { setData({ subject, counts, waiting, metrics }); setState("ok"); }
            })
            .catch((err: unknown) => {
                if (cancelled) return;
                const msg = err instanceof Error ? err.message : "";
                setState(msg.includes("404") ? "not-found" : "error");
            });
        return () => { cancelled = true; };
    }, [subjectId, refreshToken]);

    if (subjectId == null) {
        return <p style={{ padding: "16px" }}>No subject selected. Edit this widget to choose a subject.</p>;
    }
    if (state === "not-found") {
        return (
            <p style={{ padding: "16px" }}>
                Subject #{subjectId} no longer exists. Edit this widget to choose another subject.
            </p>
        );
    }
    if (state === "error") return <WidgetError />;
    if (!data) {
        return <div style={{ padding: "16px" }}><Spinner size="md" aria-label="Loading subject" /></div>;
    }

    const { subject, counts, waiting, metrics } = data;
    return (
        <div style={{ padding: "8px" }}>
            <Title headingLevel="h4" size="md">
                <Link to={`/subjects/${subject.id}`}>{subject.name}</Link>
            </Title>
            <Flex gap={{ default: "gapSm" }} style={{ marginTop: "8px" }}>
                <FlexItem>
                    <Label isCompact color={subject.active ? "green" : "grey"}>
                        {subject.active ? "Active" : "Inactive"}
                    </Label>
                </FlexItem>
                {subject.ref && <FlexItem><Label isCompact>{subject.ref}</Label></FlexItem>}
            </Flex>
            {subject.body && (
                <p style={{ marginTop: "8px", fontSize: "0.9em",
                            color: "var(--pf-t--global--text--color--subtle)" }}>
                    {stripMarkdown(subject.body)}
                </p>
            )}
            <div style={{ marginTop: "12px" }}>
                <strong>Workflow runs</strong>
                <Flex gap={{ default: "gapSm" }} style={{ marginTop: "4px" }}>
                    {RUN_STATUSES.map(status => (
                        <FlexItem key={status}>
                            <Label isCompact color={STATUS_COLORS[status]}>{status}: {counts[status] ?? 0}</Label>
                        </FlexItem>
                    ))}
                </Flex>
            </div>
            {waiting.length > 0 && (
                <div style={{ marginTop: "8px", fontSize: "0.9em" }}>
                    <strong>Waiting now</strong>
                    <ul style={{ margin: "4px 0 0 16px" }}>
                        {waiting.map(run => (
                            <li key={run.runId}>
                                <Link to={`/runs/${run.runId}`}>
                                    {run.title || run.definitionName || `Run #${run.runId}`}
                                </Link>
                                {run.currentNodeName && <span> at {run.currentNodeName}</span>}
                            </li>
                        ))}
                    </ul>
                </div>
            )}
            {metrics && (
                <div style={{ marginTop: "8px", fontSize: "0.9em" }}>
                    <strong>AI cost:</strong> ${metrics.totalCostUsd.toFixed(2)} ({metrics.invocationCount} invocations)
                </div>
            )}
        </div>
    );
}

registerWidget({
    type: "subject-spotlight",
    name: "Subject Spotlight",
    description: "One subject: its workflow runs by status, the runs waiting now and its total AI cost.",
    category: "Subjects",
    // The widget shows one chosen subject, so dashboard labels do not filter it.
    scope: "global",
    liveEvents: [SSE_EVENTS.workflowUpdated, SSE_EVENTS.subjectUpdated],
    defaultSize: { w: 4, h: 4 },
    minSize: { w: 3, h: 3 },
    configSchema: [
        { key: "subjectId", label: "Subject", type: "subject", default: null },
    ],
    component: SubjectSpotlightWidget,
});
