import { useState, useEffect } from "react";
import { ChartBar, ChartGroup, ChartVoronoiContainer, Chart, ChartAxis } from "@patternfly/react-charts/victory";
import { fetchProjects, fetchUsage } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

const TIME_WINDOWS: Record<string, number> = {
    "24h": 1, "7d": 7, "30d": 30, "90d": 90,
};

interface ProjectCost {
    name: string;
    cost: number;
}

function AiCostByProjectWidget({ config, labels }: WidgetProps) {
    const [data, setData] = useState<ProjectCost[]>([]);
    const [error, setError] = useState(false);
    const timeWindow = String(config.timeWindow || "7d");
    const maxProjects = Number(config.maxProjects) || 5;

    useEffect(() => {
        let cancelled = false;
        const days = TIME_WINDOWS[timeWindow] || 7;
        const from = new Date(Date.now() - days * 86400000).toISOString();
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;

        fetchProjects(1, 50, undefined, undefined, labelsParam)
            .then(async (result) => {
                const usagePromises = result.items.map(p =>
                    fetchUsage(1, 1, undefined, p.id, undefined, undefined, from)
                        .then(usage => ({ name: p.name, cost: usage.totalCostUsd }))
                        .catch(() => ({ name: p.name, cost: 0 }))
                );
                const costs = (await Promise.all(usagePromises))
                    .filter(c => c.cost > 0)
                    .sort((a, b) => b.cost - a.cost)
                    .slice(0, maxProjects);
                if (!cancelled) setData(costs);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels, timeWindow, maxProjects]);

    if (error) return <WidgetError />;

    if (data.length === 0) {
        return <p style={{ padding: "16px", textAlign: "center" }}>No AI usage data.</p>;
    }

    const chartData = data.map(d => ({ x: d.name, y: d.cost, name: d.name, label: `${d.name}: $${d.cost.toFixed(2)}` }));

    return (
        <div style={{ height: "100%", minHeight: "150px" }}>
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
    type: "ai-cost-by-project",
    name: "AI Cost by Project",
    description: "Top projects ranked by AI cost shown as a bar chart.",
    category: "AI & Cost",
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
        { key: "maxProjects", label: "Max Projects", type: "number", default: 5 },
    ],
    component: AiCostByProjectWidget,
});
