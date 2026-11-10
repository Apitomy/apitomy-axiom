import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, AlertActionCloseButton, AlertActionLink, PageSection } from "@patternfly/react-core";
import { useSecretExpiryCounts } from "../config/secretExpiry";

const DISMISS_KEY = "axiom.secretExpiryBanner.dismissed";

function plural(n: number, singular: string, pluralForm: string): string {
    return `${n} ${n === 1 ? singular : pluralForm}`;
}

/**
 * App-wide warning shown while any secret is expired or expiring soon. Dismissal is remembered until the
 * counts change.
 */
export function SecretExpiryBanner() {
    const navigate = useNavigate();
    const { expiring, expired } = useSecretExpiryCounts();
    const signature = `${expired}:${expiring}`;
    const [dismissed, setDismissed] = useState(() => localStorage.getItem(DISMISS_KEY));

    if (expiring + expired === 0 || dismissed === signature) return null;

    const parts: string[] = [];
    if (expired > 0) parts.push(`${plural(expired, "secret has", "secrets have")} expired`);
    if (expiring > 0) parts.push(`${plural(expiring, "secret is", "secrets are")} expiring soon`);

    return (
        <PageSection padding={{ default: "noPadding" }}>
            <Alert
                variant={expired > 0 ? "danger" : "warning"}
                isInline
                title={`${parts.join("; ")}.`}
                actionClose={<AlertActionCloseButton onClose={() => {
                    localStorage.setItem(DISMISS_KEY, signature);
                    setDismissed(signature);
                }} />}
                actionLinks={<AlertActionLink onClick={() => navigate("/secrets")}>View secrets</AlertActionLink>}
            />
        </PageSection>
    );
}
