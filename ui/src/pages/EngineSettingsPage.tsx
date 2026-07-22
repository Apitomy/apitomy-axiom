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

import { type SystemConfig, fetchSystemConfig, fetchModels } from "../config/api";

export function EngineSettingsPage() {
    const [config, setConfig] = useState<SystemConfig | null>(null);
    const [models, setModels] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        Promise.all([
            fetchSystemConfig(),
            fetchModels(),
        ])
            .then(([cfg, mdls]) => {
                setConfig(cfg);
                setModels(mdls);
            })
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
                <Title headingLevel="h1">AI Engine</Title>
                <p>Failed to load engine configuration.</p>
            </PageSection>
        );
    }

    const engineName = config.engine || "unknown";
    const engineLabel = engineName === "opencode" ? "OpenCode" : engineName === "claude-code" ? "Claude Code" : engineName;
    const engineChecks = (config.checks || []).filter(
        (c) => !["GitHub API Token", "Node.js"].includes(c.name)
    );
    const allHealthy = engineChecks.every((c) => c.status === "ok");

    // Group models by provider for OpenCode
    const isOpenCode = engineName === "opencode";
    const groupedModels: Record<string, string[]> = {};
    for (const m of models) {
        if (isOpenCode && m.includes("/")) {
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
        <PageSection>
            <Title headingLevel="h1" style={{ marginBottom: 24 }}>
                <CogIcon style={{ marginRight: 8 }} />
                AI Engine
            </Title>

            <Flex direction={{ default: "column" }} gap={{ default: "gapMd" }}>
                {/* Engine Info Card */}
                <FlexItem>
                    <Card>
                        <CardTitle>
                            <Split hasGutter>
                                <SplitItem>Active Engine</SplitItem>
                                <SplitItem isFilled />
                                <SplitItem>
                                    <Label
                                        color={allHealthy ? "green" : "red"}
                                        icon={allHealthy ? <CheckCircleIcon /> : <ExclamationCircleIcon />}
                                    >
                                        {allHealthy ? "Healthy" : "Issues detected"}
                                    </Label>
                                </SplitItem>
                            </Split>
                        </CardTitle>
                        <CardBody>
                            <DescriptionList isHorizontal>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Engine</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <Label variant="outline" color="blue">{engineLabel}</Label>
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Configuration</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <code>axiom.ai-engine={engineName}</code>
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Version</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {config.version}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                            </DescriptionList>
                        </CardBody>
                    </Card>
                </FlexItem>

                {/* Health Checks Card */}
                <FlexItem>
                    <Card>
                        <CardTitle>Health Checks</CardTitle>
                        <CardBody>
                            <DescriptionList isHorizontal>
                                {(config.checks || []).map((check) => (
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
                        </CardBody>
                    </Card>
                </FlexItem>

                {/* Available Models Card */}
                <FlexItem>
                    <Card>
                        <CardTitle>Available Models</CardTitle>
                        <CardBody>
                            {Object.entries(groupedModels).map(([provider, providerModels]) => (
                                <div key={provider} style={{ marginBottom: 16 }}>
                                    {Object.keys(groupedModels).length > 1 && (
                                        <Title headingLevel="h4" size="md" style={{ marginBottom: 8 }}>
                                            {provider}
                                        </Title>
                                    )}
                                    <Flex gap={{ default: "gapSm" }} flexWrap={{ default: "wrap" }}>
                                        {providerModels.map((m) => (
                                            <FlexItem key={m}>
                                                <Label variant="outline">{m}</Label>
                                            </FlexItem>
                                        ))}
                                    </Flex>
                                </div>
                            ))}
                            {models.length === 0 && (
                                <p className="axiom-text-subtle">No models configured.</p>
                            )}
                        </CardBody>
                    </Card>
                </FlexItem>
            </Flex>
        </PageSection>
    );
}

function StatusIcon({ status }: { status: string }) {
    if (status === "ok") {
        return (
            <Icon status="success" size="sm">
                <CheckCircleIcon />
            </Icon>
        );
    }
    if (status === "warning") {
        return (
            <Icon status="warning" size="sm">
                <ExclamationTriangleIcon />
            </Icon>
        );
    }
    return (
        <Icon status="danger" size="sm">
            <ExclamationCircleIcon />
        </Icon>
    );
}
