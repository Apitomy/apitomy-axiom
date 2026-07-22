import {
    Alert,
    AlertGroup,
    Card,
    CardBody,
    CardTitle,
    PageSection,
    Title,
} from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import { type StartupCheck } from "../config/api";

interface ConfigurationWarningProps {
    checks: StartupCheck[];
}

/**
 * Displays a full-page configuration warning when startup checks have errors.
 * Shows the status of each check with instructions for fixing problems.
 */
export function ConfigurationWarning({ checks }: ConfigurationWarningProps) {
    const errors = checks.filter((c) => c.status === "error");
    const warnings = checks.filter((c) => c.status === "warning");

    return (
        <PageSection>
            <div style={{ maxWidth: "800px", margin: "40px auto" }}>
                <Title headingLevel="h1" size="xl" style={{ marginBottom: "8px" }}>
                    Application Configuration Required
                </Title>
                <p className="axiom-text-subtle" style={{ marginBottom: "24px" }}>
                    Apitomy Axiom detected configuration issues during startup.
                    Please resolve the items below and restart the application.
                </p>

                {errors.length > 0 && (
                    <AlertGroup style={{ marginBottom: "24px" }}>
                        {errors.map((check) => (
                            <Alert
                                key={check.name}
                                variant="danger"
                                title={check.name}
                                isInline
                                style={{ marginBottom: "8px" }}
                            >
                                {check.message}
                            </Alert>
                        ))}
                    </AlertGroup>
                )}

                {warnings.length > 0 && (
                    <AlertGroup style={{ marginBottom: "24px" }}>
                        {warnings.map((check) => (
                            <Alert
                                key={check.name}
                                variant="warning"
                                title={check.name}
                                isInline
                                style={{ marginBottom: "8px" }}
                            >
                                {check.message}
                            </Alert>
                        ))}
                    </AlertGroup>
                )}

                <Card>
                    <CardTitle>Startup Check Summary</CardTitle>
                    <CardBody>
                        <table style={{ width: "100%", borderCollapse: "collapse" }}>
                            <tbody>
                                {checks.map((check) => (
                                    <tr key={check.name} style={{ borderBottom: "1px solid var(--pf-t--global--border--color--default, #d2d2d2)" }}>
                                        <td style={{ padding: "12px 8px", width: "32px" }}>
                                            {check.status === "ok" ? (
                                                <CheckCircleIcon color="var(--pf-t--global--color--status--success--default)" />
                                            ) : check.status === "warning" ? (
                                                <ExclamationTriangleIcon color="var(--pf-t--global--color--status--warning--default)" />
                                            ) : (
                                                <ExclamationCircleIcon color="var(--pf-t--global--color--status--danger--default)" />
                                            )}
                                        </td>
                                        <td style={{ padding: "12px 8px", fontWeight: "bold" }}>
                                            {check.name}
                                        </td>
                                        <td className="axiom-text-subtle" style={{ padding: "12px 8px" }}>
                                            {check.status === "ok" ? "Configured" : check.message}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </CardBody>
                </Card>
            </div>
        </PageSection>
    );
}
