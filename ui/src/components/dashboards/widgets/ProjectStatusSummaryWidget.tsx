import { useState, useEffect } from "react";
import { Gallery, GalleryItem } from "@patternfly/react-core";
import { useNavigate } from "react-router-dom";
import { fetchProjects } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

const STATUS_COLORS: Record<string, string> = {
    Created: "var(--pf-t--global--color--status--info--default)",
    InProgress: "var(--pf-t--global--color--status--success--default)",
    Idle: "var(--pf-t--global--color--status--warning--default)",
    Completed: "var(--pf-t--global--color--status--custom--default)",
    Failed: "var(--pf-t--global--color--status--danger--default)",
};

function ProjectStatusSummaryWidget({ labels }: WidgetProps) {
    const navigate = useNavigate();
    const [counts, setCounts] = useState<Record<string, number>>({});
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        const labelsParam = labels.length > 0 ? labels.join(",") : undefined;
        fetchProjects(1, 1000, undefined, undefined, labelsParam)
            .then(result => {
                if (cancelled) return;
                const c: Record<string, number> = {};
                for (const p of result.items) {
                    c[p.status] = (c[p.status] || 0) + 1;
                }
                setCounts(c);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [labels]);

    if (error) return <WidgetError />;

    const statuses = ["Created", "InProgress", "Idle", "Completed", "Failed"];

    return (
        <Gallery hasGutter minWidths={{ default: "100px" }}>
            {statuses.map(status => (
                <GalleryItem key={status}>
                    <div style={{
                        textAlign: "center", padding: "12px", borderRadius: "8px",
                        backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                        cursor: "pointer",
                        borderLeft: `4px solid ${STATUS_COLORS[status] || "#888"}`,
                    }} onClick={() => navigate(`/projects?status=${status}`)}>
                        <div style={{ fontSize: "1.75rem", fontWeight: 700 }}>
                            {counts[status] || 0}
                        </div>
                        <div style={{ fontSize: "0.85rem" }}>
                            {status === "InProgress" ? "In Progress" : status}
                        </div>
                    </div>
                </GalleryItem>
            ))}
        </Gallery>
    );
}

registerWidget({
    type: "project-status-summary",
    name: "Project Status Summary",
    description: "Colored stat cards showing project counts by status.",
    category: "Projects",
    defaultSize: { w: 6, h: 2 },
    minSize: { w: 4, h: 2 },
    configSchema: [],
    component: ProjectStatusSummaryWidget,
});
