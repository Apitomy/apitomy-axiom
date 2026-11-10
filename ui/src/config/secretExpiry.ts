import { useEffect, useState } from "react";
import { fetchSecrets, type Secret } from "./api";
import { sseClient, type AxiomSseEvent } from "./sse";

/** Human label for a secret's expiry, e.g. "Expires in 5d". */
export function expiryLabel(secret: Secret, now: Date = new Date()): string {
    if (secret.expiryStatus === "NONE" || !secret.expiresOn) return "Never";
    if (secret.expiryStatus === "EXPIRED") return "Expired";
    const ms = new Date(secret.expiresOn).getTime() - now.getTime();
    const days = Math.floor(ms / 86_400_000);
    if (days >= 1) return `Expires in ${days}d`;
    return `Expires in ${Math.max(1, Math.floor(ms / 3_600_000))}h`;
}

/** Counts secrets that are expiring soon or expired. */
export function countExpiry(secrets: Secret[]): { expiring: number; expired: number } {
    return {
        expiring: secrets.filter((s) => s.expiryStatus === "EXPIRING").length,
        expired: secrets.filter((s) => s.expiryStatus === "EXPIRED").length,
    };
}

/**
 * Converts a local datetime-input value ("YYYY-MM-DDTHH:mm") to the UTC instant format the API accepts
 * ("yyyy-MM-ddTHH:mm:ssZ", no fractional seconds).
 */
export function toApiInstant(localValue: string): string {
    return new Date(localValue).toISOString().replace(/\.\d{3}Z$/, "Z");
}

/** Live expiring/expired counts, refreshed on {@code secret-expiry} SSE events. */
export function useSecretExpiryCounts(): { expiring: number; expired: number } {
    const [counts, setCounts] = useState({ expiring: 0, expired: 0 });
    useEffect(() => {
        const load = () => fetchSecrets().then((list) => setCounts(countExpiry(list))).catch(() => {});
        load();
        return sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "secret-expiry") load();
        });
    }, []);
    return counts;
}
