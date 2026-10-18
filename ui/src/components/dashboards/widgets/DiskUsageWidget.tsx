import { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { ChartBar, ChartGroup, ChartVoronoiContainer, Chart, ChartAxis } from "@patternfly/react-charts/victory";
import { fetchDiskUsage, formatBytes } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

interface SubjectDisk {
    name: string;
    bytes: number;
}

function DiskUsageWidget({ config }: WidgetProps) {
    const [data, setData] = useState<SubjectDisk[]>([]);
    const [totalBytes, setTotalBytes] = useState(0);
    const [error, setError] = useState(false);

    const maxSubjects = Number(config.maxSubjects) || 10;

    useEffect(() => {
        let cancelled = false;
        fetchDiskUsage(1, maxSubjects, undefined, "diskUsageBytes", "desc")
            .then(result => {
                if (cancelled) return;
                const items = result.items
                    .map(p => ({ name: p.subjectName, bytes: p.diskUsageBytes }))
                    .filter(p => p.bytes > 0);
                setData(items);
                setTotalBytes(result.totalDiskUsageBytes);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [maxSubjects]);

    if (error) return <WidgetError />;

    if (data.length === 0) {
        return (
            <p style={{ padding: "16px", textAlign: "center" }}>
                No disk usage data. <Link to="/metrics/disk-usage">Disk usage</Link>
            </p>
        );
    }

    const chartData = data.map(d => ({
        x: d.name,
        y: d.bytes,
        name: d.name,
        label: `${d.name}: ${formatBytes(d.bytes)}`,
    }));

    return (
        <div style={{ height: "100%", minHeight: "150px" }}>
            <div style={{ textAlign: "center", padding: "4px 0", fontSize: "0.85rem" }}>
                Total: {formatBytes(totalBytes)}{" "}
                <Link to="/metrics/disk-usage">View details</Link>
            </div>
            <Chart
                containerComponent={
                    <ChartVoronoiContainer
                        labels={({ datum }: { datum: { label: string } }) => datum.label}
                    />
                }
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
    description: "Subjects using the most disk space, shown as a bar chart. Covers all subjects (not filtered by dashboard labels).",
    category: "System",
    scope: "global",
    defaultSize: { w: 4, h: 3 },
    minSize: { w: 3, h: 3 },
    configSchema: [
        { key: "maxSubjects", label: "Max Subjects", type: "number", default: 10, min: 1, max: 20 },
    ],
    component: DiskUsageWidget,
});
