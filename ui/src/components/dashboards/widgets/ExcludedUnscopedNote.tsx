/**
 * Small note shown by AI cost widgets when dashboard labels exclude AI cost that has no subject.
 *
 * @param amount the excluded unscoped cost in USD; nothing is rendered when absent or zero
 */
export function ExcludedUnscopedNote({ amount }: { amount?: number }) {
    if (!amount || amount <= 0) return null;
    return (
        <div style={{ fontSize: "0.8rem", color: "var(--pf-t--global--text--color--subtle)", padding: "2px 4px" }}>
            Excludes ${amount.toFixed(2)} of AI cost not linked to a subject (dashboard labels apply).
        </div>
    );
}
