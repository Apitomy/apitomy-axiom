import { useState, useEffect } from "react";
import { ChartBar, ChartGroup, ChartVoronoiContainer, Chart, ChartAxis } from "@patternfly/react-charts/victory";
import { fetchDiskUsage, formatBytes } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

interface ProjectDiskUsage {
    name: string;
    bytes: number;
}

function DiskUsageWidget({ config }: WidgetProps) {
    const [data, setData] = useState<ProjectDiskUsage[]>([]);
    const [totalBytes, setTotalBytes] = useState(0);
    const [error, setError] = useState(false);
    const maxProjects = Number(config.maxProjects) || 10;

    useEffect(() => {
        let cancelled = false;
        fetchDiskUsage(1, 50)
            .then((result) => {
                if (cancelled) return;
                const projects = result.items
                    .filter(p => p.diskUsageBytes > 0)
                    .sort((a, b) => b.diskUsageBytes - a.diskUsageBytes)
                    .slice(0, maxProjects)
                    .map(p => ({ name: p.projectName, bytes: p.diskUsageBytes }));
                setData(projects);
                setTotalBytes(result.totalDiskUsageBytes);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxProjects]);

    if (error) return <WidgetError />;

    if (data.length === 0) {
        return <p style={{ padding: "16px", textAlign: "center" }}>No disk usage data.</p>;
    }

    const chartData = data.map(d => ({ x: d.name, y: d.bytes, name: d.name, label: `${d.name}: ${formatBytes(d.bytes)}` }));

    return (
        <div style={{ height: "100%", minHeight: "150px" }}>
            <p style={{ padding: "8px 16px 0", margin: 0, fontSize: "13px", color: "var(--pf-t--global--color--200)" }}>
                Total: {formatBytes(totalBytes)}
            </p>
            <Chart
                containerComponent={<ChartVoronoiContainer labels={({ datum }: { datum: { label: string } }) => datum.label} />}
                domainPadding={{ x: [30, 30] }}
                padding={{ bottom: 50, left: 70, right: 20, top: 10 }}
                height={200}
            >
                <ChartAxis />
                <ChartAxis dependentAxis showGrid tickFormat={(t: number) => formatBytes(t)} />
                <ChartGroup>
                    <ChartBar data={chartData} />
                </ChartGroup>
            </Chart>
        </div>
    );
}

registerWidget({
    type: "disk-usage",
    name: "Disk Usage Breakdown",
    description: "Storage consumption broken down by project shown as a bar chart.",
    category: "System",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 3 },
    configSchema: [
        { key: "maxProjects", label: "Max Projects", type: "number", default: 10, min: 1, max: 20 },
    ],
    component: DiskUsageWidget,
});
