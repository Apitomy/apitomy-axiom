import { useState, useEffect } from "react";
import { ChartBar, ChartGroup, ChartVoronoiContainer, Chart, ChartAxis } from "@patternfly/react-charts/victory";
import { fetchAiUsageSummary } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";
import { ExcludedUnscopedNote } from "./ExcludedUnscopedNote";

const TIME_WINDOWS: Record<string, number> = {
    "24h": 1, "7d": 7, "30d": 30, "90d": 90,
};

interface SubjectCost {
    name: string;
    cost: number;
}

function AiCostBySubjectWidget({ config, labels }: WidgetProps) {
    const [data, setData] = useState<SubjectCost[]>([]);
    const [excluded, setExcluded] = useState<number | undefined>(undefined);
    const [error, setError] = useState(false);
    const timeWindow = String(config.timeWindow || "7d");
    const maxSubjects = Number(config.maxSubjects) || 5;

    useEffect(() => {
        let cancelled = false;
        const days = TIME_WINDOWS[timeWindow] || 7;
        const since = new Date(Date.now() - days * 86400000).toISOString();
        fetchAiUsageSummary("subject", { since, labels, limit: maxSubjects })
            .then(summary => {
                if (cancelled) return;
                setData(summary.rows
                    .filter(r => r.costUsd > 0)
                    .map(r => ({ name: r.label, cost: r.costUsd })));
                setExcluded(summary.excludedUnscopedCostUsd);
                setError(false);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, timeWindow, maxSubjects]);

    if (error) return <WidgetError />;

    if (data.length === 0) {
        return <p style={{ padding: "16px", textAlign: "center" }}>No AI usage data.</p>;
    }

    const chartData = data.map(d => ({ x: d.name, y: d.cost, name: d.name, label: `${d.name}: $${d.cost.toFixed(2)}` }));

    return (
        <div style={{ height: "100%", minHeight: "150px" }}>
            <ExcludedUnscopedNote amount={excluded} />
            <Chart
                containerComponent={<ChartVoronoiContainer labels={({ datum }: { datum: { label: string } }) => datum.label} />}
                domainPadding={{ x: [30, 30] }}
                padding={{ bottom: 50, left: 60, right: 20, top: 10 }}
                height={200}
            >
                <ChartAxis />
                <ChartAxis dependentAxis showGrid tickFormat={(t: number) => `$${t}`} />
                <ChartGroup>
                    <ChartBar data={chartData} />
                </ChartGroup>
            </Chart>
        </div>
    );
}

registerWidget({
    type: "ai-cost-by-subject",
    name: "AI Cost by Subject",
    description: "Top subjects ranked by AI cost in the selected time window, shown as a bar chart.",
    category: "AI & Cost",
    scope: "subject-labels",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 3 },
    configSchema: [
        {
            key: "timeWindow", label: "Time Window", type: "select", default: "7d",
            options: [
                { label: "24 Hours", value: "24h" },
                { label: "7 Days", value: "7d" },
                { label: "30 Days", value: "30d" },
                { label: "90 Days", value: "90d" },
            ],
        },
        { key: "maxSubjects", label: "Max Subjects", type: "number", default: 5 },
    ],
    component: AiCostBySubjectWidget,
});
