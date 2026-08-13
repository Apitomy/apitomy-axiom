import { useEffect, useState } from "react";
import { Tooltip } from "@patternfly/react-core";

interface FromNowProps {
    date: string | Date;
}

/**
 * Displays a relative time string (e.g. "3 hours ago") with a tooltip
 * showing the full date-time. Automatically refreshes every 60 seconds.
 */
export function FromNow({ date }: FromNowProps) {
    const [, setTick] = useState(0);

    useEffect(() => {
        const interval = setInterval(() => setTick((t) => t + 1), 60000);
        return () => clearInterval(interval);
    }, []);

    const d = typeof date === "string" ? new Date(date) : date;
    const full = d.toLocaleString();

    return (
        <Tooltip content={full}>
            <span style={{ whiteSpace: "nowrap", cursor: "default" }}>
                {formatRelative(d)}
            </span>
        </Tooltip>
    );
}

function formatRelative(date: Date): string {
    const now = Date.now();
    const diffMs = now - date.getTime();
    const absDiff = Math.abs(diffMs);
    const future = diffMs < 0;

    const seconds = Math.floor(absDiff / 1000);
    if (seconds < 60) return "just now";

    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return future ? `in ${minutes}m` : `${minutes}m ago`;

    const hours = Math.floor(minutes / 60);
    if (hours < 24) return future ? `in ${hours}h` : `${hours}h ago`;

    const days = Math.floor(hours / 24);
    if (days < 30) return future ? `in ${days}d` : `${days}d ago`;

    const months = Math.floor(days / 30);
    if (months < 12) return future ? `in ${months}mo` : `${months}mo ago`;

    const years = Math.floor(months / 12);
    return future ? `in ${years}y` : `${years}y ago`;
}
