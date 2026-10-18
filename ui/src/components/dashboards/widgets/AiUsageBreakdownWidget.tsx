import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchAiUsageSummary, type AiUsageGroupBy, type AiUsageSummary } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { ExcludedUnscopedNote } from "./ExcludedUnscopedNote";
import { cellStyle, sinceField, sinceIso, WidgetEmpty, WidgetLoading } from "./widgetUtils";

const GROUP_BY_OPTIONS: { label: string; value: AiUsageGroupBy }[] = [
    { label: "Subject", value: "subject" },
    { label: "Workflow", value: "workflow" },
    { label: "Engine", value: "engine" },
    { label: "Model", value: "model" },
    { label: "Action Type", value: "actionType" },
];

/**
 * Link target for a summary row key, when the dimension has a detail page.
 *
 * @param groupBy the grouping dimension
 * @param key the row key ("" for none)
 * @returns the route, or undefined
 */
export function rowLink(groupBy: AiUsageGroupBy, key: string): string | undefined {
    if (!key) return undefined;
    if (groupBy === "subject") return `/subjects/${key}`;
    if (groupBy === "workflow") return `/components/workflows/${key}`;
    return undefined;
}

function AiUsageBreakdownWidget({ config, labels }: WidgetProps) {
    const [summary, setSummary] = useState<AiUsageSummary | null>(null);
    const [error, setError] = useState(false);
    const rawGroupBy = String(config.groupBy || "workflow");
    const groupBy = (GROUP_BY_OPTIONS.some(o => o.value === rawGroupBy) ? rawGroupBy : "workflow") as AiUsageGroupBy;
    const since = String(config.since || "7d");
    const limit = Math.min(100, Math.max(1, Number(config.limit) || 10));
    const labelKey = labels.join(",");

    useEffect(() => {
        let cancelled = false;
        setSummary(null);
        fetchAiUsageSummary(groupBy, { since: sinceIso(since), labels, limit })
            .then(s => { if (!cancelled) { setSummary(s); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [groupBy, since, limit, labelKey]);

    if (error) return <WidgetError />;
    if (!summary) return <WidgetLoading label="Loading AI usage" />;

    const groupLabel = GROUP_BY_OPTIONS.find(o => o.value === groupBy)?.label ?? groupBy;
    const max = Math.max(...summary.rows.map(r => r.costUsd), 0);

    return (
        <div>
            <ExcludedUnscopedNote amount={summary.excludedUnscopedCostUsd} />
            {summary.rows.length === 0 ? <WidgetEmpty>No AI usage data.</WidgetEmpty> : (
                <table style={{ width: "100%", borderCollapse: "collapse" }}>
                    <thead>
                        <tr>
                            <th style={cellStyle}>{groupLabel}</th>
                            <th style={cellStyle}>Cost</th>
                            <th style={cellStyle}>Tokens (in/out)</th>
                            <th style={cellStyle}>Calls</th>
                        </tr>
                    </thead>
                    <tbody>
                        {summary.rows.map(r => {
                            const link = rowLink(groupBy, r.key);
                            const width = max > 0 ? `${Math.round((r.costUsd / max) * 100)}%` : "0%";
                            return (
                                <tr key={r.key || "(none)"}>
                                    <td style={cellStyle}>
                                        {link ? <Link to={link}>{r.label}</Link> : r.label}
                                        <div style={{ height: "4px", width, marginTop: "2px",
                                                      background: "var(--pf-t--global--color--brand--default)" }} />
                                    </td>
                                    <td style={cellStyle}>${r.costUsd.toFixed(2)}</td>
                                    <td style={cellStyle}>
                                        {r.inputTokens.toLocaleString()} / {r.outputTokens.toLocaleString()}
                                    </td>
                                    <td style={cellStyle}>{r.invocations}</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}
            <div style={{ fontSize: "0.8rem", textAlign: "right", padding: "4px" }}>
                Total: ${summary.totalCostUsd.toFixed(2)}
            </div>
        </div>
    );
}

registerWidget({
    type: "ai-usage-breakdown",
    name: "AI Usage Breakdown",
    description: "AI cost, tokens and calls grouped by subject, workflow, engine, model or action type. "
        + "Dashboard labels filter by subject; usage without a subject is then excluded.",
    category: "AI & Cost",
    scope: "subject-labels",
    defaultSize: { w: 6, h: 3 },
    minSize: { w: 4, h: 2 },
    configSchema: [
        { key: "groupBy", label: "Group By", type: "select", default: "workflow", options: GROUP_BY_OPTIONS },
        sinceField("7d"),
        { key: "limit", label: "Max Rows", type: "number", default: 10, min: 1, max: 100 },
    ],
    component: AiUsageBreakdownWidget,
});
