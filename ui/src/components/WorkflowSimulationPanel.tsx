import { useState } from "react";
import {
    Alert,
    Button,
    Card,
    CardBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    Label,
    TextArea,
    Title,
} from "@patternfly/react-core";
import {
    startSimulation,
    stepSimulation,
    runSimulation,
    resumeSimulation,
} from "@apitomy/flow-ui";
import type { Workflow, SimState, SimMock } from "@apitomy/flow-ui";

interface WorkflowSimulationPanelProps {
    workflow: Workflow;
}

const SIM_STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue",
    blocked: "orange",
    completed: "green",
    failed: "red",
};

/**
 * A minimal, Axiom-owned simulation panel for dry-running a {@link Workflow} definition and
 * testing its EL conditions before publishing.
 *
 * This is intentionally a plain form/state-display panel (no canvas highlighting or node-click
 * integration) — those richer UX affordances live in flow-ui's internal {@code SimulationPanel}
 * component, which is not part of the package's public API surface. This component only relies on
 * the pure simulation functions (`startSimulation`, `stepSimulation`, `runSimulation`,
 * `resumeSimulation`) that flow-ui does export.
 */
export function WorkflowSimulationPanel({ workflow }: WorkflowSimulationPanelProps) {
    const [contextText, setContextText] = useState("{}");
    const [contextError, setContextError] = useState<string | null>(null);
    const [simState, setSimState] = useState<SimState | null>(null);
    const [mockText, setMockText] = useState("{}");
    const [mockError, setMockError] = useState<string | null>(null);

    const handleStart = () => {
        setContextError(null);
        let context: Record<string, unknown>;
        try {
            context = JSON.parse(contextText);
        } catch {
            setContextError("Start context must be valid JSON.");
            return;
        }
        setSimState(startSimulation(workflow, context));
    };

    const handleStep = () => {
        if (!simState) return;
        setSimState(stepSimulation(workflow, simState));
    };

    const handleRun = () => {
        if (!simState) return;
        setSimState(runSimulation(workflow, simState));
    };

    const handleReset = () => {
        setSimState(null);
        setContextError(null);
        setMockError(null);
        setMockText("{}");
    };

    const handleResume = () => {
        if (!simState || simState.status !== "blocked") return;
        setMockError(null);
        let output: Record<string, unknown>;
        try {
            output = JSON.parse(mockText);
        } catch {
            setMockError("Mock output must be valid JSON.");
            return;
        }
        const mock: SimMock = { output };
        setSimState(resumeSimulation(workflow, simState, mock, simState.blockedOn?.nodeId));
    };

    const activeBranchNodeIds = simState?.activeBranches.map((b) => b.nodeId) ?? [];

    return (
        <Card>
            <CardBody>
                <Title headingLevel="h3" size="md" style={{ marginBottom: "12px" }}>
                    Simulate
                </Title>

                <Form>
                    <FormGroup label="Start context (JSON)" fieldId="sim-context">
                        <TextArea
                            id="sim-context"
                            value={contextText}
                            onChange={(_e, v) => setContextText(v)}
                            rows={4}
                            isDisabled={simState !== null}
                            resizeOrientation="vertical"
                        />
                    </FormGroup>
                    {contextError && (
                        <Alert variant="danger" isInline title={contextError} />
                    )}

                    <Flex spaceItems={{ default: "spaceItemsSm" }} style={{ marginTop: "12px" }}>
                        <FlexItem>
                            <Button variant="primary" onClick={handleStart} isDisabled={simState !== null}>
                                Start
                            </Button>
                        </FlexItem>
                        <FlexItem>
                            <Button
                                variant="secondary"
                                onClick={handleStep}
                                isDisabled={!simState || simState.status !== "running"}
                            >
                                Step
                            </Button>
                        </FlexItem>
                        <FlexItem>
                            <Button
                                variant="secondary"
                                onClick={handleRun}
                                isDisabled={!simState || simState.status !== "running"}
                            >
                                Run
                            </Button>
                        </FlexItem>
                        <FlexItem>
                            <Button variant="link" onClick={handleReset} isDisabled={!simState}>
                                Reset
                            </Button>
                        </FlexItem>
                    </Flex>
                </Form>

                {simState && (
                    <div style={{ marginTop: "20px" }}>
                        <Flex alignItems={{ default: "alignItemsCenter" }} spaceItems={{ default: "spaceItemsSm" }}>
                            <FlexItem>
                                <strong>Status:</strong>
                            </FlexItem>
                            <FlexItem>
                                <Label isCompact color={SIM_STATUS_COLORS[simState.status] || "grey"}>
                                    {simState.status}
                                </Label>
                            </FlexItem>
                        </Flex>

                        <div style={{ marginTop: "8px" }}>
                            <strong>{activeBranchNodeIds.length > 1 ? "Active branches:" : "Current node:"}</strong>{" "}
                            {activeBranchNodeIds.length > 1
                                ? activeBranchNodeIds.join(", ") || "—"
                                : simState.currentNodeId || "—"}
                        </div>

                        <div style={{ marginTop: "8px" }}>
                            <strong>Visited path:</strong>{" "}
                            {simState.visitedNodeIds.length > 0
                                ? simState.visitedNodeIds.join(" → ")
                                : "—"}
                        </div>

                        {simState.status === "failed" && simState.error && (
                            <Alert
                                variant="danger"
                                isInline
                                title={simState.error.message}
                                style={{ marginTop: "12px" }}
                            >
                                {(simState.error.nodeId || simState.error.edgeId) && (
                                    <p>
                                        {simState.error.nodeId && <>Node: {simState.error.nodeId} </>}
                                        {simState.error.edgeId && <>Edge: {simState.error.edgeId}</>}
                                    </p>
                                )}
                            </Alert>
                        )}

                        {simState.status === "blocked" && simState.blockedOn && (
                            <Card isCompact style={{ marginTop: "12px" }}>
                                <CardBody>
                                    <p style={{ marginBottom: "8px" }}>
                                        Blocked on node <strong>{simState.blockedOn.nodeId}</strong>{" "}
                                        (kind: {simState.blockedOn.kind}). Supply a mock output to
                                        resume.
                                    </p>
                                    <FormGroup label="Mock output (JSON)" fieldId="sim-mock">
                                        <TextArea
                                            id="sim-mock"
                                            value={mockText}
                                            onChange={(_e, v) => setMockText(v)}
                                            rows={3}
                                            resizeOrientation="vertical"
                                        />
                                    </FormGroup>
                                    {mockError && (
                                        <Alert variant="danger" isInline title={mockError} style={{ marginTop: "8px" }} />
                                    )}
                                    <Button
                                        variant="primary"
                                        onClick={handleResume}
                                        style={{ marginTop: "8px" }}
                                    >
                                        Resume
                                    </Button>
                                </CardBody>
                            </Card>
                        )}
                    </div>
                )}
            </CardBody>
        </Card>
    );
}
