import { useState, useEffect } from "react";
import {
    Card,
    CardBody,
    CardTitle,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    Flex,
    FlexItem,
    Icon,
    Label,
    PageSection,
    Spinner,
    Split,
    SplitItem,
    Title,
} from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import CogIcon from "@patternfly/react-icons/dist/esm/icons/cog-icon";

import { type EngineInfo, type SystemConfig, fetchSystemConfig } from "../config/api";

export function EngineSettingsPage() {
    const [config, setConfig] = useState<SystemConfig | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        fetchSystemConfig()
            .then(setConfig)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    if (loading) {
        return (
            <PageSection>
                <Spinner size="lg" />
            </PageSection>
        );
    }

    if (!config) {
        return (
            <PageSection>
                <Title headingLevel="h1">AI Engines</Title>
                <p>Failed to load engine configuration.</p>
            </PageSection>
        );
    }

    const engines = config.engines || [];
    const defaultEngine = config.defaultEngine || config.engine;
    const nodeJsCheck = (config.checks || []).find((c) => c.name === "Node.js");

    return (
        <PageSection>
            <Title headingLevel="h1" style={{ marginBottom: 24 }}>
                <CogIcon style={{ marginRight: 8 }} />
                AI Engines
            </Title>

            <Flex direction={{ default: "column" }} gap={{ default: "gapMd" }}>
                {/* Node.js check (shared prerequisite) */}
                {nodeJsCheck && (
                    <FlexItem>
                        <Card>
                            <CardTitle>Prerequisites</CardTitle>
                            <CardBody>
                                <DescriptionList isHorizontal>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>
                                            <StatusIcon status={nodeJsCheck.status} />{" "}
                                            {nodeJsCheck.name}
                                        </DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {nodeJsCheck.message}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                </DescriptionList>
                            </CardBody>
                        </Card>
                    </FlexItem>
                )}

                {/* One card per engine */}
                {engines.map((engine) => (
                    <FlexItem key={engine.type}>
                        <EngineCard
                            engine={engine}
                            isDefault={engine.type === defaultEngine}
                        />
                    </FlexItem>
                ))}

                {engines.length === 0 && (
                    <FlexItem>
                        <Card>
                            <CardBody>
                                <p>No AI engines registered.</p>
                            </CardBody>
                        </Card>
                    </FlexItem>
                )}
            </Flex>
        </PageSection>
    );
}

function EngineCard({ engine, isDefault }: { engine: EngineInfo; isDefault: boolean }) {
    const checks = engine.checks || [];
    const models = engine.models || [];

    // Group models by provider (for engines that use provider/model format)
    const groupedModels: Record<string, string[]> = {};
    for (const m of models) {
        if (m.includes("/")) {
            const [provider, ...rest] = m.split("/");
            const key = provider.charAt(0).toUpperCase() + provider.slice(1);
            if (!groupedModels[key]) groupedModels[key] = [];
            groupedModels[key].push(rest.join("/"));
        } else {
            if (!groupedModels["Models"]) groupedModels["Models"] = [];
            groupedModels["Models"].push(m);
        }
    }

    return (
        <Card>
            <CardTitle>
                <Split hasGutter>
                    <SplitItem>
                        {engine.label}
                        {isDefault && (
                            <Label
                                color="blue"
                                style={{ marginLeft: 8 }}
                                isCompact
                            >
                                Default
                            </Label>
                        )}
                    </SplitItem>
                    <SplitItem isFilled />
                    <SplitItem>
                        <Label
                            color={engine.available ? "green" : "red"}
                            icon={
                                engine.available ? (
                                    <CheckCircleIcon />
                                ) : (
                                    <ExclamationCircleIcon />
                                )
                            }
                        >
                            {engine.available ? "Available" : "Unavailable"}
                        </Label>
                        {engine.supportsInteractiveSessions && (
                            <Label
                                color="purple"
                                style={{ marginLeft: 8 }}
                                isCompact
                            >
                                Interactive
                            </Label>
                        )}
                    </SplitItem>
                </Split>
            </CardTitle>
            <CardBody>
                <Flex direction={{ default: "column" }} gap={{ default: "gapMd" }}>
                    {/* Health Checks */}
                    {checks.length > 0 && (
                        <FlexItem>
                            <Title headingLevel="h4" size="md" style={{ marginBottom: 8 }}>
                                Health Checks
                            </Title>
                            <DescriptionList isHorizontal>
                                {checks.map((check) => (
                                    <DescriptionListGroup key={check.name}>
                                        <DescriptionListTerm>
                                            <StatusIcon status={check.status} />{" "}
                                            {check.name}
                                        </DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {check.message}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                ))}
                            </DescriptionList>
                        </FlexItem>
                    )}

                    {/* Available Models */}
                    {models.length > 0 && (
                        <FlexItem>
                            <Title headingLevel="h4" size="md" style={{ marginBottom: 8 }}>
                                Available Models
                            </Title>
                            {Object.entries(groupedModels).map(
                                ([provider, providerModels]) => (
                                    <div key={provider} style={{ marginBottom: 8 }}>
                                        {Object.keys(groupedModels).length > 1 && (
                                            <Title
                                                headingLevel="h5"
                                                size="md"
                                                style={{ marginBottom: 4 }}
                                            >
                                                {provider}
                                            </Title>
                                        )}
                                        <Flex
                                            gap={{ default: "gapSm" }}
                                            flexWrap={{ default: "wrap" }}
                                        >
                                            {providerModels.map((m) => (
                                                <FlexItem key={m}>
                                                    <Label variant="outline">{m}</Label>
                                                </FlexItem>
                                            ))}
                                        </Flex>
                                    </div>
                                )
                            )}
                        </FlexItem>
                    )}

                    {models.length === 0 && (
                        <FlexItem>
                            <p className="axiom-text-subtle">No models configured.</p>
                        </FlexItem>
                    )}
                </Flex>
            </CardBody>
        </Card>
    );
}

function StatusIcon({ status }: { status: string }) {
    if (status === "ok") {
        return (
            <Icon status="success">
                <CheckCircleIcon />
            </Icon>
        );
    }
    if (status === "warning") {
        return (
            <Icon status="warning">
                <ExclamationTriangleIcon />
            </Icon>
        );
    }
    return (
        <Icon status="danger">
            <ExclamationCircleIcon />
        </Icon>
    );
}
