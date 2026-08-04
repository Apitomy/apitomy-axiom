import { useState, useEffect } from "react";
import { Gallery, GalleryItem } from "@patternfly/react-core";
import { fetchUsage } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";
import { WidgetError } from "../WidgetError";

const TIME_WINDOWS: Record<string, number> = {
    "24h": 1, "7d": 7, "30d": 30, "90d": 90,
};

function AiCostSummaryWidget({ config }: WidgetProps) {
    const [totalCost, setTotalCost] = useState(0);
    const [totalInput, setTotalInput] = useState(0);
    const [totalOutput, setTotalOutput] = useState(0);
    const [invocations, setInvocations] = useState(0);
    const [error, setError] = useState(false);

    const timeWindow = String(config.timeWindow || "7d");

    useEffect(() => {
        let cancelled = false;
        const days = TIME_WINDOWS[timeWindow] || 7;
        const from = new Date(Date.now() - days * 86400000).toISOString();
        fetchUsage(1, 1, undefined, undefined, undefined, undefined, from)
            .then(result => {
                if (cancelled) return;
                setTotalCost(result.totalCostUsd);
                setTotalInput(result.totalInputTokens);
                setTotalOutput(result.totalOutputTokens);
                setInvocations(result.totalCount);
            })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, [timeWindow]);

    if (error) return <WidgetError />;

    const formatCost = (cost: number) => `$${cost.toFixed(2)}`;
    const formatTokens = (tokens: number) => {
        if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1)}M`;
        if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(0)}K`;
        return String(tokens);
    };

    const stats = [
        { label: "Total Cost", value: formatCost(totalCost) },
        { label: "Invocations", value: String(invocations) },
        { label: "Input Tokens", value: formatTokens(totalInput) },
        { label: "Output Tokens", value: formatTokens(totalOutput) },
    ];

    return (
        <Gallery hasGutter minWidths={{ default: "100px" }}>
            {stats.map(s => (
                <GalleryItem key={s.label}>
                    <div style={{
                        textAlign: "center", padding: "12px", borderRadius: "8px",
                        backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                    }}>
                        <div style={{ fontSize: "1.5rem", fontWeight: 700 }}>{s.value}</div>
                        <div style={{ fontSize: "0.8rem" }}>{s.label}</div>
                    </div>
                </GalleryItem>
            ))}
        </Gallery>
    );
}

registerWidget({
    type: "ai-cost-summary",
    name: "AI Cost Summary",
    description: "Total AI spending, token counts, and invocations for a time period.",
    category: "AI & Cost",
    defaultSize: { w: 4, h: 2 },
    minSize: { w: 3, h: 2 },
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
    ],
    component: AiCostSummaryWidget,
});
