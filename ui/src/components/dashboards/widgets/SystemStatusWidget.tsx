import { useState, useEffect } from "react";
import { Flex, FlexItem, Label } from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import { fetchSystemHealth, type SystemHealth } from "../../../config/api";
import { registerWidget, type WidgetProps } from "../widget-registry";

function SystemStatusWidget(_props: WidgetProps) {
    const [health, setHealth] = useState<SystemHealth | null>(null);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        fetchSystemHealth()
            .then(h => { if (!cancelled) { setHealth(h); setError(false); } })
            .catch(() => { if (!cancelled) setError(true); });
        return () => { cancelled = true; };
    }, []);

    const isUp = !error && health?.status === "UP";

    return (
        <div style={{ padding: "12px", textAlign: "center" }}>
            <Flex justifyContent={{ default: "justifyContentCenter" }}
                  alignItems={{ default: "alignItemsCenter" }}
                  gap={{ default: "gapSm" }}>
                <FlexItem>
                    {isUp ? (
                        <CheckCircleIcon color="var(--pf-t--global--color--status--success--default)"
                                         style={{ fontSize: "1.5rem" }} />
                    ) : (
                        <ExclamationCircleIcon color="var(--pf-t--global--color--status--danger--default)"
                                               style={{ fontSize: "1.5rem" }} />
                    )}
                </FlexItem>
                <FlexItem>
                    <Label isCompact color={isUp ? "green" : "red"}>
                        {isUp ? "UP" : "DOWN"}
                    </Label>
                </FlexItem>
                {health?.version && (
                    <FlexItem>
                        <span style={{ fontSize: "0.85em", color: "var(--pf-t--global--color--200)" }}>
                            v{health.version}
                        </span>
                    </FlexItem>
                )}
            </Flex>
        </div>
    );
}

registerWidget({
    type: "system-status",
    name: "System Status",
    description: "Simple UP/DOWN health indicator with version.",
    category: "System",
    defaultSize: { w: 2, h: 1 },
    minSize: { w: 2, h: 1 },
    configSchema: [],
    component: SystemStatusWidget,
});
