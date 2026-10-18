import { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { Flex, FlexItem, Label, Spinner } from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import { fetchSystemHealth, type HealthStatus, type SystemHealth } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";

/** How often the widget re-fetches application health. */
export const HEALTH_POLL_MS = 30000;

/** Display names and links for known health checks. */
const CHECK_INFO: Record<string, { label: string; link?: string }> = {
    database: { label: "Database" },
    eventSources: { label: "Event sources", link: "/events/sources" },
    engines: { label: "Engines", link: "/engine" },
    scheduler: { label: "Scheduler" },
};

function StatusIcon({ status }: { status: HealthStatus }) {
    if (status === "UP") {
        return <CheckCircleIcon aria-label="UP" color="var(--pf-t--global--icon--color--status--success--default)" />;
    }
    if (status === "DEGRADED") {
        return <ExclamationTriangleIcon aria-label="DEGRADED"
                                        color="var(--pf-t--global--icon--color--status--warning--default)" />;
    }
    return <ExclamationCircleIcon aria-label="DOWN" color="var(--pf-t--global--icon--color--status--danger--default)" />;
}

function statusColor(status: HealthStatus): "green" | "orange" | "red" {
    if (status === "UP") return "green";
    if (status === "DEGRADED") return "orange";
    return "red";
}

function SystemStatusWidget(_props: WidgetProps) {
    const [health, setHealth] = useState<SystemHealth | null>(null);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        const load = () => {
            fetchSystemHealth()
                .then(h => { if (!cancelled) { setHealth(h); setError(false); } })
                .catch(() => { if (!cancelled) setError(true); });
        };
        load();
        const timer = setInterval(load, HEALTH_POLL_MS);
        return () => { cancelled = true; clearInterval(timer); };
    }, []);

    if (!health && !error) {
        return <div style={{ padding: "12px", textAlign: "center" }}><Spinner size="md" aria-label="Loading health" /></div>;
    }

    // An unreachable health endpoint means the application cannot be reached: show DOWN.
    const overall: HealthStatus = error || !health ? "DOWN" : health.status;
    const checks = error ? [] : (health?.checks ?? []);

    return (
        <div style={{ padding: "8px 12px" }}>
            <Flex alignItems={{ default: "alignItemsCenter" }} gap={{ default: "gapSm" }}>
                <FlexItem style={{ fontSize: "1.5rem" }}><StatusIcon status={overall} /></FlexItem>
                <FlexItem><Label isCompact color={statusColor(overall)}>{overall}</Label></FlexItem>
                {error && <FlexItem><span style={{ fontSize: "0.85em" }}>Health endpoint unreachable</span></FlexItem>}
                {!error && health?.version && (
                    <FlexItem>
                        <span style={{ fontSize: "0.85em", color: "var(--pf-t--global--text--color--subtle)" }}>
                            v{health.version}
                        </span>
                    </FlexItem>
                )}
            </Flex>
            {checks.length > 0 && (
                <ul style={{ listStyle: "none", padding: 0, margin: "8px 0 0 0", fontSize: "0.85rem" }}>
                    {checks.map(check => {
                        const info = CHECK_INFO[check.name] ?? { label: check.name };
                        return (
                            <li key={check.name} style={{ padding: "2px 0" }}>
                                <StatusIcon status={check.status} />{" "}
                                {info.link ? <Link to={info.link}>{info.label}</Link> : info.label}
                                {check.message && (
                                    <span style={{ color: "var(--pf-t--global--text--color--subtle)" }}>
                                        {" "}- {check.message}
                                    </span>
                                )}
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}

registerWidget({
    type: "system-status",
    name: "Application Health",
    description: "Overall application health (UP, DEGRADED or DOWN) with the database, event source, engine "
        + "and scheduler checks. Refreshes every 30 seconds.",
    category: "System",
    scope: "global",
    defaultSize: { w: 3, h: 2 },
    minSize: { w: 2, h: 1 },
    configSchema: [],
    component: SystemStatusWidget,
});
